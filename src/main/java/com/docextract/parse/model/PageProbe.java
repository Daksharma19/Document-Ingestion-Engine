package com.docextract.parse.model;

/**
 * Cheap facts about one page, measured before any parsing decision. {@code meaningfulChars} counts letters and
 * digits in the text layer; {@code garbageRatio} is the share of glyphs that decode to nothing readable
 * (replacement characters, control or private-use code points: a broken font encoding); {@code imageAreaRatio}
 * is the share of the page covered by drawn images (capped at 1); {@code inkRatio} (only measured for pages with
 * neither text nor images) is the share of non-white pixels in a coarse render; {@code functionWordRatio} is the
 * text layer's share of function words ({@link com.docextract.parse.quality.TextQuality}), null when not judged.
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record PageProbe(double width, double height, int meaningfulChars, int lines, double garbageRatio,
                        double imageAreaRatio, Double inkRatio, Double functionWordRatio) {
}
