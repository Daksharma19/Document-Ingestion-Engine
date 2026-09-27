package com.docextract.strategy;

import com.docextract.document.Document;
import com.docextract.strategy.text.TextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NativePdfTextStrategy implements ExtractionStrategy {

    private final TextExtractor textExtractor;

    public NativePdfTextStrategy(TextExtractor textExtractor) {
        this.textExtractor = textExtractor;
    }

    @Override
    public ExtractionResult extract(Document document, JsonNode schema) {
        String text = textExtractor.extractText(document);
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        schema.path("properties").fieldNames().forEachRemaining(name -> {
            fields.put(name, null);
            confidence.put(name, 0.0);
        });
        return new ExtractionResult(fields, confidence, text, costTier().costUnits());
    }

    @Override
    public CostTier costTier() {
        return CostTier.NATIVE_TEXT;
    }

    @Override
    public String name() {
        return "native-pdf-text";
    }
}
