package com.docextract.parse.pdf;

import java.util.List;

/** What one parse of a PDF knows about a page (1-based {@code index}); {@code lines} are segments, in content order. */
public record PdfPage(int index, double width, double height, List<TextLine> lines, int glyphs, int badGlyphs,
                      double imageAreaRatio, Double inkRatio, List<com.docextract.parse.model.BBox> images) {
}
