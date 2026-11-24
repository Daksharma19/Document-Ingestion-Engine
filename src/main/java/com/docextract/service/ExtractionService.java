package com.docextract.service;

import com.docextract.api.FileExtractionResult;
import com.docextract.api.FileExtractionResult.Status;
import com.docextract.document.Document;
import com.docextract.document.DocumentKind;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.document.DocumentTypeDetector.DetectedType;
import com.docextract.schema.DocumentClassifier;
import com.docextract.schema.SchemaCatalog;
import com.docextract.schema.SchemaCatalog.SchemaPreset;
import com.docextract.strategy.CostTier;
import com.docextract.strategy.ExtractionException;
import com.docextract.strategy.ExtractionResult;
import com.docextract.strategy.ExtractionStrategy;
import com.docextract.strategy.TierNotConfiguredException;
import com.docextract.strategy.UnsupportedDocumentException;
import com.docextract.validation.ValidationEngine;
import com.docextract.validation.ValidationReport;
import com.docextract.validation.expr.RuleExpression;
import com.docextract.validation.expr.RuleExpression.RuleSyntaxException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);
    /** Documents per request after archives are unpacked; the controller separately caps uploaded parts at 20. */
    static final int MAX_FILES = 500;

    private final ObjectMapper mapper;
    private final List<ExtractionStrategy> strategies;
    private final ValidationEngine validationEngine;
    private final DocumentTypeDetector detector;
    private final TierRouter router;
    private final SchemaCatalog catalog;
    private final DocumentClassifier classifier;
    private final boolean escalate;
    private final boolean escalateToVision;
    /** Files of one request are extracted concurrently (native text is CPU-bound, OCR waits on the service). */
    private final ExecutorService pool;

    @Autowired
    public ExtractionService(ObjectMapper mapper, List<ExtractionStrategy> strategies,
                             ValidationEngine validationEngine, DocumentTypeDetector detector, TierRouter router,
                             SchemaCatalog catalog, DocumentClassifier classifier,
                             @Value("${docextract.routing.escalate:true}") boolean escalate,
                             @Value("${docextract.routing.escalate-to-vision:false}") boolean escalateToVision,
                             @Value("${docextract.parallelism:0}") int parallelism) {
        this.mapper = mapper;
        this.strategies = strategies;
        this.validationEngine = validationEngine;
        this.detector = detector;
        this.router = router;
        this.catalog = catalog;
        this.classifier = classifier;
        this.escalate = escalate;
        this.escalateToVision = escalateToVision;
        int threads = parallelism > 0 ? parallelism : Math.max(2, Runtime.getRuntime().availableProcessors());
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "extract");
            t.setDaemon(true);
            return t;
        });
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * What the request asked for: a JSON Schema, a built-in schema by id, or {@code auto} (classify each document
     * and use the matching built-in schema). {@code rules} are the request's rules, or null to use the preset's.
     */
    private record SchemaRequest(JsonNode schema, SchemaPreset preset, boolean auto, List<RuleExpression> rules) {
    }

    /** One tier's try at a document, with the schema it was mapped onto and its validation. */
    private record Attempt(ExtractionStrategy strategy, ExtractionResult result, JsonNode schema, SchemaPreset preset,
                           ValidationReport report, int costUnits) {

        int missingRequired() {
            int n = 0;
            for (JsonNode r : schema.path("required")) {
                if (result.fields().get(r.asText()) == null) {
                    n++;
                }
            }
            return n;
        }

        long filled() {
            return result.fields().values().stream().filter(Objects::nonNull).count();
        }

        /** Valid beats invalid; then fewer empty required fields; then more fields; then confidence. */
        double score() {
            double conf = result.confidence().values().stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
            return (report.isValid() ? 10_000 : 0) - missingRequired() * 1_000 + filled() * 10 + conf;
        }
    }

    /** Request-level problems (no files, bad schema, bad rule, unknown tier) are 400s; per-file problems are per-file results. */
    public List<FileExtractionResult> extract(List<MultipartFile> files, String schemaJson, String rulesJson, String tier,
                                              boolean includeText) {
        if (files == null || files.isEmpty()) {
            throw new InvalidRequestException("at least one file is required (multipart part 'files')");
        }
        if (files.size() > MAX_FILES) {
            throw new InvalidRequestException("at most " + MAX_FILES + " documents per request");
        }
        List<RuleExpression> rules = rulesJson == null || rulesJson.isBlank() ? null : parseRules(rulesJson);
        SchemaRequest request = resolveSchema(schemaJson, rules);
        ExtractionStrategy forced = resolveTier(tier);

        if (files.size() == 1) {
            return List.of(timed(files.getFirst(), request, forced, includeText));
        }
        List<Future<FileExtractionResult>> futures = new ArrayList<>();
        for (MultipartFile file : files) {
            futures.add(pool.submit(() -> timed(file, request, forced, includeText)));
        }
        List<FileExtractionResult> results = new ArrayList<>();
        for (Future<FileExtractionResult> f : futures) {
            try {
                results.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while extracting", e);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
            }
        }
        return results;
    }

    private FileExtractionResult timed(MultipartFile file, SchemaRequest request, ExtractionStrategy forced,
                                       boolean includeText) {
        long start = System.nanoTime();
        FileExtractionResult r = extractOne(file, request, forced, includeText);
        long ms = (System.nanoTime() - start) / 1_000_000;
        return new FileExtractionResult(r.filename(), r.detectedType(), r.documentKind(), r.status(), r.tier(),
                r.strategy(), r.routing(), r.engine(), r.schemaId(), r.documentType(), r.costUnits(), ms, r.fields(),
                r.confidence(), r.fieldValidation(), r.valid(), r.violations(), r.rules(), r.error(), r.text());
    }

    private FileExtractionResult extractOne(MultipartFile file, SchemaRequest request, ExtractionStrategy forced,
                                            boolean includeText) {
        String name = file.getOriginalFilename();
        if (file.isEmpty()) {
            return failure(name, null, null, Status.ERROR, "file is empty");
        }
        Document document;
        try {
            document = new Document(name, file.getContentType(), file.getBytes());
        } catch (IOException e) {
            return failure(name, null, null, Status.ERROR, "could not read uploaded file");
        }
        DetectedType type = detector.detect(document);
        ExtractionStrategy strategy = forced;
        try {
            String routing = "requested";
            if (forced == null) {
                TierRouter.Choice choice = router.route(document, type);
                strategy = choice.strategy();
                routing = choice.reason();
            }
            Attempt best = attempt(strategy, document, request, 0);
            if (forced == null && escalate) {
                Attempt first = best;
                for (ExtractionStrategy next : escalationsFrom(strategy, type.kind())) {
                    if (!weak(best)) {
                        break;
                    }
                    try {
                        Attempt tried = attempt(next, document, request, best.costUnits());
                        String why = describeWeakness(best);
                        best = tried.score() > best.score() ? tried : withCost(best, tried.costUnits());
                        routing += "; escalated to " + next.name() + " (" + why + ")"
                                + (best.strategy() == next ? "" : ", kept " + best.strategy().name() + " result");
                    } catch (TierNotConfiguredException | UnsupportedDocumentException | ExtractionException e) {
                        log.info("escalation to {} skipped for {}: {}", next.name(), name, e.getMessage());
                    }
                }
                if (best != first && best.strategy() != first.strategy()) {
                    strategy = best.strategy();
                }
            }
            ExtractionResult result = best.result();
            return new FileExtractionResult(name, type.mediaType(), type.kind().name(), Status.OK,
                    best.strategy().costTier().name(), best.strategy().name(), routing, result.engine(),
                    best.preset() == null ? null : best.preset().id(),
                    request.auto() && best.preset() != null ? best.preset().label() : null,
                    best.costUnits(), 0, result.fields(), result.confidence(), best.report().fieldPassed(best.schema()),
                    best.report().isValid(), best.report().violations(), best.report().ruleResults(), null,
                    includeText ? result.rawText() : null);
        } catch (TierNotConfiguredException e) {
            return failure(name, type, strategy, Status.TIER_NOT_CONFIGURED, e.getMessage());
        } catch (UnsupportedDocumentException e) {
            return failure(name, type, strategy, Status.UNSUPPORTED, e.getMessage());
        } catch (ExtractionException e) {
            log.warn("extraction failed for {}", name, e);
            String cause = e.getCause() != null && e.getCause().getMessage() != null ? ": " + e.getCause().getMessage() : "";
            return failure(name, type, strategy, Status.ERROR, e.getMessage() + cause);
        }
    }

    /** Runs one tier. With {@code schema=auto} the document is read once, classified, then mapped onto the preset. */
    private Attempt attempt(ExtractionStrategy strategy, Document document, SchemaRequest request, int costSoFar) {
        SchemaPreset preset = request.preset();
        JsonNode schema = request.schema();
        ExtractionResult result;
        if (request.auto()) {
            ExtractionResult read = strategy.extract(document, mapper.createObjectNode());
            preset = classifier.classify(read.rawText()).preset();
            schema = preset.schema();
            JsonNode chosen = schema;
            result = strategy.remap(read, chosen).orElseGet(() -> strategy.extract(document, chosen));
        } else {
            result = strategy.extract(document, schema);
        }
        List<RuleExpression> rules = request.rules() != null ? request.rules()
                : preset != null ? presetRules(preset) : List.of();
        ValidationReport report = validationEngine.validateExtraction(mapper.valueToTree(result.fields()), schema, rules);
        return new Attempt(strategy, result, schema, preset, report, costSoFar + result.costUnits());
    }

    private static Attempt withCost(Attempt a, int costUnits) {
        return new Attempt(a.strategy(), a.result(), a.schema(), a.preset(), a.report(), costUnits);
    }

    /**
     * A result worth a second opinion from a dearer tier: a required field is empty, validation failed, or (with
     * nothing required) most fields came back empty, which is what a PDF whose text layer is partial looks like.
     */
    static boolean weak(Attempt a) {
        if (a.missingRequired() > 0 || !a.report().isValid()) {
            return true;
        }
        int total = a.result().fields().size();
        return a.schema().path("required").isEmpty() && total > 0 && a.filled() * 2 < total;
    }

    private static String describeWeakness(Attempt a) {
        int missing = a.missingRequired();
        if (missing > 0) {
            return a.strategy().name() + " left " + missing + " required field" + (missing == 1 ? "" : "s") + " empty";
        }
        if (!a.report().isValid()) {
            return a.strategy().name() + " result failed validation";
        }
        return a.strategy().name() + " found only " + a.filled() + " of " + a.result().fields().size() + " fields";
    }

    /** Dearer tiers that can still read this document: native text -> OCR (PDFs) -> vision (when enabled). */
    private List<ExtractionStrategy> escalationsFrom(ExtractionStrategy from, DocumentKind kind) {
        return strategies.stream()
                .filter(s -> s.costTier().costUnits() > from.costTier().costUnits())
                .filter(s -> s.supports(kind) && s.notConfiguredReason().isEmpty())
                .filter(s -> s.costTier() != CostTier.VISION_LLM || escalateToVision)
                .sorted(Comparator.comparingInt(s -> s.costTier().costUnits()))
                .toList();
    }

    private static FileExtractionResult failure(String name, DetectedType type, ExtractionStrategy strategy,
                                                Status status, String error) {
        return new FileExtractionResult(name,
                type == null ? null : type.mediaType(), type == null ? null : type.kind().name(), status,
                strategy == null ? null : strategy.costTier().name(), strategy == null ? null : strategy.name(),
                null, null, null, null, 0, 0, Map.of(), Map.of(), null, null, null, null, error, null);
    }

    private ExtractionStrategy resolveTier(String tier) {
        if (tier == null || tier.isBlank() || tier.equalsIgnoreCase("auto")) {
            return null;
        }
        return strategies.stream()
                .filter(s -> s.name().equalsIgnoreCase(tier) || s.costTier().name().equalsIgnoreCase(tier))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException("unknown tier '" + tier + "'; use auto or one of "
                        + strategies.stream().map(ExtractionStrategy::name).toList()));
    }

    private List<RuleExpression> parseRules(String rulesJson) {
        JsonNode node;
        try {
            node = mapper.readTree(rulesJson);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("rules must be a JSON array of strings: " + e.getOriginalMessage());
        }
        if (!node.isArray()) {
            throw new InvalidRequestException("rules must be a JSON array of strings");
        }
        List<RuleExpression> out = new ArrayList<>();
        for (JsonNode r : node) {
            if (!r.isTextual()) {
                throw new InvalidRequestException("rules must be a JSON array of strings, got " + r);
            }
            out.add(parseRule(r.asText()));
        }
        return out;
    }

    private static RuleExpression parseRule(String rule) {
        try {
            return RuleExpression.parse(rule);
        } catch (RuleSyntaxException e) {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    private static List<RuleExpression> presetRules(SchemaPreset preset) {
        return preset.rules().stream().map(ExtractionService::parseRule).toList();
    }

    private SchemaRequest resolveSchema(String schemaText, List<RuleExpression> rules) {
        if (schemaText == null || schemaText.isBlank()) {
            throw new InvalidRequestException("schema is required (a JSON Schema, a built-in schema id, or 'auto')");
        }
        String t = schemaText.strip();
        if (t.equalsIgnoreCase("auto")) {
            return new SchemaRequest(null, null, true, rules);
        }
        if (!t.startsWith("{")) {
            SchemaPreset preset = catalog.find(t).orElseThrow(() -> new InvalidRequestException(
                    "schema is neither JSON nor a built-in schema id; built-in ids: "
                            + catalog.all().stream().map(SchemaPreset::id).toList() + " (or 'auto')"));
            return new SchemaRequest(preset.schema(), preset, false, rules);
        }
        JsonNode node;
        try {
            node = mapper.readTree(t);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("schema is not valid JSON: " + e.getOriginalMessage());
        }
        if (!node.isObject()) {
            throw new InvalidRequestException("schema must be a JSON object");
        }
        try {
            ValidationEngine.schemaRules(node);
        } catch (RuleSyntaxException e) {
            throw new InvalidRequestException("schema x-rules: " + e.getMessage());
        }
        return new SchemaRequest(node, null, false, rules == null ? List.of() : rules);
    }
}
