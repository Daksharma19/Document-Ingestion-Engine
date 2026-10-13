package com.docextract.validation;

import java.util.List;

/** Outcome of one declarative rule expression on one extraction. */
public record RuleResult(String expression, Outcome outcome, String detail, List<String> fields) {

    public enum Outcome {
        PASSED,
        FAILED,
        /** A referenced value was absent, so the rule could not be judged; the schema's "required" covers absence. */
        SKIPPED
    }
}
