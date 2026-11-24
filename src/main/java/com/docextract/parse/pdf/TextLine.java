package com.docextract.parse.pdf;

import com.docextract.parse.model.BBox;

/**
 * One visual line of a PDF text layer. Wide horizontal gaps inside the line are written as three spaces
 * ({@link PdfPageReader#COLUMN_GAP}), so table cells and side-by-side columns stay apart.
 */
public record TextLine(String text, BBox bbox, float fontSize, boolean bold, double confidence, boolean figure,
                       boolean italic) {

    public TextLine(String text, BBox bbox, float fontSize, boolean bold, double confidence, boolean figure) {
        this(text, bbox, fontSize, bold, confidence, figure, false);
    }

    /** A line from a text layer: exact, so confidence 1. */
    public TextLine(String text, BBox bbox, float fontSize, boolean bold) {
        this(text, bbox, fontSize, bold, 1.0, false);
    }

    public TextLine(String text, BBox bbox, float fontSize, boolean bold, double confidence) {
        this(text, bbox, fontSize, bold, confidence, false);
    }

    /** The text OCR read inside an image on a text page: kept whole as one figure block. */
    public static TextLine figure(String text, BBox bbox, double confidence) {
        return new TextLine(text, bbox, 0, false, confidence, true);
    }

    public double height() {
        return bbox.y1() - bbox.y0();
    }
}
