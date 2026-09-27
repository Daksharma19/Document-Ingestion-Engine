package com.docextract.strategy;

import java.util.Map;

public record ExtractionResult(
        Map<String, Object> fields,
        Map<String, Double> confidence,
        String rawText,
        int costUnits) {
}
