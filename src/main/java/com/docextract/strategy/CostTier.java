package com.docextract.strategy;

/** Extraction tiers ordered cheapest first; costUnits are relative, not currency. */
public enum CostTier {
    NATIVE_TEXT(1),
    LAYOUT(5),
    OCR(20),
    VISION_LLM(100);

    private final int costUnits;

    CostTier(int costUnits) {
        this.costUnits = costUnits;
    }

    public int costUnits() {
        return costUnits;
    }
}
