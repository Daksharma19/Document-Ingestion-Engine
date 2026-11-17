package com.docextract.parse.model;

/** How a page was read, cheapest first. Cost units are relative weights, not money. */
public enum PageMethod {
    /** Embedded text layer (PDFBox) or the file's own structure (Tika: DOCX, XLSX, HTML, text). */
    NATIVE(1),
    OCR(20),
    LAYOUT(40),
    VISION(100);

    private final int costUnits;

    PageMethod(int costUnits) {
        this.costUnits = costUnits;
    }

    public int costUnits() {
        return costUnits;
    }
}
