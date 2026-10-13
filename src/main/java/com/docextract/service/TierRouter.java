package com.docextract.service;

import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector.DetectedType;
import com.docextract.strategy.ExtractionStrategy;
import com.docextract.strategy.NativeTextStrategy;
import com.docextract.strategy.OcrStrategy;
import com.docextract.strategy.text.TextExtractor;
import org.springframework.stereotype.Component;

/**
 * Picks the cheapest tier that can read the document:
 * PDF with a real text layer -> native text; image-only PDF or image -> OCR; Office/text -> native text (Tika).
 * The vision-LLM tier is only used when explicitly requested (escalation on validation failure comes later).
 */
@Component
public class TierRouter {

    public record Choice(ExtractionStrategy strategy, String reason) {
    }

    private final NativeTextStrategy nativeText;
    private final OcrStrategy ocr;
    private final TextExtractor textExtractor;

    public TierRouter(NativeTextStrategy nativeText, OcrStrategy ocr, TextExtractor textExtractor) {
        this.nativeText = nativeText;
        this.ocr = ocr;
        this.textExtractor = textExtractor;
    }

    public Choice route(Document document, DetectedType type) {
        return switch (type.kind()) {
            case PDF -> textExtractor.hasTextLayer(document)
                    ? new Choice(nativeText, "PDF has a text layer")
                    : new Choice(ocr, "PDF has no usable text layer (scanned/image-only)");
            case IMAGE -> new Choice(ocr, "image file (" + type.mediaType() + ")");
            case OFFICE_OR_TEXT -> new Choice(nativeText, "text-bearing format (" + type.mediaType() + ") read via Tika");
            case UNKNOWN -> new Choice(nativeText, "unrecognised type; trying Tika text");
        };
    }
}
