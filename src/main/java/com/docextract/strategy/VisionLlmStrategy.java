package com.docextract.strategy;

import com.docextract.document.Document;
import com.docextract.document.DocumentKind;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.strategy.text.PageImages;
import com.docextract.strategy.vision.OpenAiVisionClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Most expensive tier: page images + the JSON Schema go to a vision LLM, whose JSON reply is
 * projected onto the schema's top-level properties. Unknown keys from the model are dropped.
 */
@Component
public class VisionLlmStrategy implements ExtractionStrategy {

    /** LLMs give no calibrated per-field probability; this is a fixed prior for a non-null answer. */
    static final double MODEL_CONFIDENCE = 0.9;
    static final int VISION_DPI = 150;

    private final OpenAiVisionClient client;
    private final DocumentTypeDetector detector;
    private final ObjectMapper json;
    private final int maxPages;

    public VisionLlmStrategy(OpenAiVisionClient client, DocumentTypeDetector detector, ObjectMapper json,
                             @Value("${docextract.vision.max-pages:3}") int maxPages) {
        this.client = client;
        this.detector = detector;
        this.json = json;
        this.maxPages = maxPages;
    }

    @Override
    public ExtractionResult extract(Document document, JsonNode schema) {
        notConfiguredReason().ifPresent(r -> {
            throw new TierNotConfiguredException(r);
        });
        DocumentKind kind = detector.detect(document).kind();
        if (!supports(kind)) {
            throw new UnsupportedDocumentException("vision tier needs a PDF or image, got " + kind + " for " + document.filename());
        }
        OpenAiVisionClient.VisionReply reply = client.extract(PageImages.render(document, kind, VISION_DPI, maxPages), schema);

        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        schema.path("properties").fieldNames().forEachRemaining(name -> {
            JsonNode v = reply.fields().path(name);
            boolean present = !v.isMissingNode() && !v.isNull();
            fields.put(name, present ? json.convertValue(v, Object.class) : null);
            confidence.put(name, present ? MODEL_CONFIDENCE : 0.0);
        });
        String raw = "model=" + client.model() + " promptTokens=" + reply.promptTokens()
                + " completionTokens=" + reply.completionTokens();
        return new ExtractionResult(fields, confidence, raw, costTier().costUnits());
    }

    @Override
    public boolean supports(DocumentKind kind) {
        return kind == DocumentKind.PDF || kind == DocumentKind.IMAGE;
    }

    @Override
    public Optional<String> notConfiguredReason() {
        return client.notConfiguredReason();
    }

    @Override
    public CostTier costTier() {
        return CostTier.VISION_LLM;
    }

    @Override
    public String name() {
        return "vision-llm";
    }
}
