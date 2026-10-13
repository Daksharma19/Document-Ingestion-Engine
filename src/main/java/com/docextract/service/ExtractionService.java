package com.docextract.service;

import com.docextract.api.FileExtractionResult;
import com.docextract.api.FileExtractionResult.Status;
import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.document.DocumentTypeDetector.DetectedType;
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
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);
    static final int MAX_FILES = 20;

    private final ObjectMapper mapper;
    private final List<ExtractionStrategy> strategies;
    private final ValidationEngine validationEngine;
    private final DocumentTypeDetector detector;
    private final TierRouter router;

    public ExtractionService(ObjectMapper mapper, List<ExtractionStrategy> strategies,
                             ValidationEngine validationEngine, DocumentTypeDetector detector, TierRouter router) {
        this.mapper = mapper;
        this.strategies = strategies;
        this.validationEngine = validationEngine;
        this.detector = detector;
        this.router = router;
    }

    /** Request-level problems (no files, bad schema, bad rule, unknown tier) are 400s; per-file problems are per-file results. */
    public List<FileExtractionResult> extract(List<MultipartFile> files, String schemaJson, String rulesJson, String tier,
                                              boolean includeText) {
        if (files == null || files.isEmpty()) {
            throw new InvalidRequestException("at least one file is required (multipart part 'files')");
        }
        if (files.size() > MAX_FILES) {
            throw new InvalidRequestException("at most " + MAX_FILES + " files per request");
        }
        JsonNode schema = parseSchema(schemaJson);
        List<RuleExpression> rules = parseRules(rulesJson);
        try {
            ValidationEngine.schemaRules(schema);
        } catch (RuleSyntaxException e) {
            throw new InvalidRequestException("schema x-rules: " + e.getMessage());
        }
        ExtractionStrategy forced = resolveTier(tier);

        List<FileExtractionResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(extractOne(file, schema, rules, forced, includeText));
        }
        return results;
    }

    private FileExtractionResult extractOne(MultipartFile file, JsonNode schema, List<RuleExpression> rules,
                                            ExtractionStrategy forced, boolean includeText) {
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
            ExtractionResult result = strategy.extract(document, schema);
            ValidationReport report = validationEngine.validateExtraction(mapper.valueToTree(result.fields()), schema, rules);
            return new FileExtractionResult(name, type.mediaType(), type.kind().name(), Status.OK,
                    strategy.costTier().name(), strategy.name(), routing, result.costUnits(),
                    result.fields(), result.confidence(), report.fieldPassed(schema), report.isValid(),
                    report.violations(), report.ruleResults(), null, includeText ? result.rawText() : null);
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

    private static FileExtractionResult failure(String name, DetectedType type, ExtractionStrategy strategy,
                                                Status status, String error) {
        return new FileExtractionResult(name,
                type == null ? null : type.mediaType(), type == null ? null : type.kind().name(), status,
                strategy == null ? null : strategy.costTier().name(), strategy == null ? null : strategy.name(),
                null, 0, Map.of(), Map.of(), null, null, null, null, error, null);
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
        if (rulesJson == null || rulesJson.isBlank()) {
            return List.of();
        }
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
            try {
                out.add(RuleExpression.parse(r.asText()));
            } catch (RuleSyntaxException e) {
                throw new InvalidRequestException(e.getMessage());
            }
        }
        return out;
    }

    private JsonNode parseSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new InvalidRequestException("schema is required");
        }
        try {
            JsonNode node = mapper.readTree(schemaJson);
            if (!node.isObject()) {
                throw new InvalidRequestException("schema must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("schema is not valid JSON: " + e.getOriginalMessage());
        }
    }
}
