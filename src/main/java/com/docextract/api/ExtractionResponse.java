package com.docextract.api;

import java.util.Map;

public record ExtractionResponse(
        Map<String, Object> fields,
        Map<String, Double> confidence,
        String tier,
        int costUnits) {
}
