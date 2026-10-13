package com.docextract.validation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ValidationReport(List<Violation> violations, List<RuleResult> ruleResults) {

    public ValidationReport(List<Violation> violations) {
        this(violations, List.of());
    }

    public boolean isValid() {
        return violations.isEmpty();
    }

    /** One entry per top-level schema property: true unless some violation implicates that field. */
    public Map<String, Boolean> fieldPassed(JsonNode schema) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        schema.path("properties").fieldNames().forEachRemaining(name -> out.put(name, true));
        for (Violation v : violations) {
            for (String f : v.relatedFields()) {
                out.put(f, false);
            }
        }
        return out;
    }
}
