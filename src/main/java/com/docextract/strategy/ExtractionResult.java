package com.docextract.strategy;

import java.util.Map;

/**
 * @param engine         what actually read the document within the tier (e.g. "rapidocr-ppocr", "tesseract"), or null
 * @param textConfidence mean OCR confidence of {@code rawText} (0..1), or null when the text was not recognised
 */
public record ExtractionResult(
        Map<String, Object> fields,
        Map<String, Double> confidence,
        String rawText,
        int costUnits,
        String engine,
        Double textConfidence) {

    public ExtractionResult(Map<String, Object> fields, Map<String, Double> confidence, String rawText, int costUnits) {
        this(fields, confidence, rawText, costUnits, null, null);
    }
}
