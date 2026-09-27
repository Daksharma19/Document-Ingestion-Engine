package com.docextract.validation;

/** One specific problem with an extraction: which field, which rule, and a human-readable reason. */
public record Violation(String field, String rule, String message) {
}
