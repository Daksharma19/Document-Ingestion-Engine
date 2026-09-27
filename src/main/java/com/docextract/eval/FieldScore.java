package com.docextract.eval;

import com.fasterxml.jackson.databind.JsonNode;

public record FieldScore(String field, JsonNode expected, JsonNode predicted, FieldOutcome outcome, double confidence) {
}
