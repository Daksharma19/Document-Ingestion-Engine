package com.docextract.eval;

import java.util.List;

/**
 * Score for one document under one strategy.
 *
 * @param error non-null when the strategy threw; the document then scores every field as MISSING
 */
public record DocumentScore(
        String document,
        String strategy,
        List<FieldScore> fields,
        int costUnits,
        int violationCount,
        String error) {

    public long count(FieldOutcome outcome) {
        return fields.stream().filter(f -> f.outcome() == outcome).count();
    }

    /** Fraction of labelled fields that are correct; a document with no labelled fields scores 1. */
    public double accuracy() {
        return fields.isEmpty() ? 1.0 : (double) count(FieldOutcome.CORRECT) / fields.size();
    }

    public boolean exactMatch() {
        return count(FieldOutcome.CORRECT) == fields.size();
    }

    public boolean passedValidation() {
        return error == null && violationCount == 0;
    }

    /** The dangerous case for a router: validation says fine, but the answer is wrong. */
    public boolean validButWrong() {
        return passedValidation() && !exactMatch();
    }
}
