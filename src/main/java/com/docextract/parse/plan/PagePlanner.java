package com.docextract.parse.plan;

import com.docextract.parse.model.PageMethod;
import com.docextract.parse.model.PageProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Picks the cheapest method likely to read a page well, from its probe alone. Later stages may still escalate a
 * page whose result scores badly; this is only the first guess, and every guess carries its reason.
 */
@Component
public class PagePlanner {

    /** {@code verify}: read with {@code method}, then confirm with OCR and keep the better reading. */
    public record Plan(PageMethod method, String reason, boolean verify) {

        Plan(PageMethod method, String reason) {
            this(method, reason, false);
        }
    }

    private final int minChars;
    private final double maxGarbage;
    private final double imagePageRatio;

    public PagePlanner(@Value("${docextract.parse.min-chars-per-page:20}") int minChars,
                       @Value("${docextract.parse.max-garbage-ratio:0.10}") double maxGarbage,
                       @Value("${docextract.parse.image-page-ratio:0.60}") double imagePageRatio) {
        this.minChars = minChars;
        this.maxGarbage = maxGarbage;
        this.imagePageRatio = imagePageRatio;
    }

    public Plan plan(PageProbe p) {
        if (p.meaningfulChars() == 0 && p.imageAreaRatio() == 0 && p.inkRatio() != null && p.inkRatio() < 0.002) {
            return new Plan(PageMethod.NATIVE, "blank page");
        }
        if (p.meaningfulChars() < minChars) {
            return p.imageAreaRatio() > 0
                    ? new Plan(PageMethod.OCR, "no text layer; page is an image (%.0f%% covered)".formatted(p.imageAreaRatio() * 100))
                    : new Plan(PageMethod.OCR, "no text layer (" + p.meaningfulChars() + " letters/digits)");
        }
        if (p.garbageRatio() > maxGarbage) {
            return new Plan(PageMethod.OCR, "text layer is garbled (%.0f%% unreadable glyphs)".formatted(p.garbageRatio() * 100));
        }
        if (p.imageAreaRatio() >= imagePageRatio && p.meaningfulChars() < minChars * 10) {
            return new Plan(PageMethod.OCR, "page is mostly an image (%.0f%%) with little text (%d chars)"
                    .formatted(p.imageAreaRatio() * 100, p.meaningfulChars()));
        }
        if (p.functionWordRatio() != null && p.functionWordRatio() < 0.04) {
            // letters that decode fine but spell nothing: a font with a wrong encoding (or a dense table);
            // OCR decides which reading is right
            return new Plan(PageMethod.NATIVE, "text layer does not read as language (%.0f%% function words)"
                    .formatted(p.functionWordRatio() * 100), true);
        }
        return new Plan(PageMethod.NATIVE, "text layer present (" + p.meaningfulChars() + " letters/digits)");
    }
}
