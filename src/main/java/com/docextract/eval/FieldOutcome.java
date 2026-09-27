package com.docextract.eval;

public enum FieldOutcome {
    /** Predicted value equals the label after normalisation. */
    CORRECT,
    /** A non-null value was predicted but it differs from the label. */
    WRONG,
    /** Nothing was predicted for a labelled field. */
    MISSING
}
