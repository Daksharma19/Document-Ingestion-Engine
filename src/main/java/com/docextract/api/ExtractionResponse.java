package com.docextract.api;

import com.docextract.validation.Violation;

import java.util.List;
import java.util.Map;

public record ExtractionResponse(
        Map<String, Object> fields,
        Map<String, Double> confidence,
        String tier,
        int costUnits,
        List<Violation> violations) {
}
