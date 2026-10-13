package com.docextract.strategy;

import com.docextract.document.Document;
import com.docextract.document.DocumentKind;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.strategy.mapping.FieldMapper;
import com.docextract.strategy.ocr.OcrEngine;
import com.docextract.strategy.text.PageImages;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Renders pages to images, runs Tesseract, then maps fields from the OCR text with the same mapper as native text. */
@Component
public class OcrStrategy implements ExtractionStrategy {

    private final OcrEngine ocr;
    private final FieldMapper fieldMapper;
    private final DocumentTypeDetector detector;
    private final int maxPages;

    public OcrStrategy(OcrEngine ocr, FieldMapper fieldMapper, DocumentTypeDetector detector,
                       @Value("${docextract.ocr.max-pages:10}") int maxPages) {
        this.ocr = ocr;
        this.fieldMapper = fieldMapper;
        this.detector = detector;
        this.maxPages = maxPages;
    }

    @Override
    public ExtractionResult extract(Document document, JsonNode schema) {
        notConfiguredReason().ifPresent(r -> {
            throw new TierNotConfiguredException(r);
        });
        DocumentKind kind = detector.detect(document).kind();
        if (!supports(kind)) {
            throw new UnsupportedDocumentException("OCR needs a PDF or image, got " + kind + " for " + document.filename());
        }
        String text = ocr.recognise(PageImages.render(document, kind, PageImages.OCR_DPI, maxPages));
        FieldMapper.MappedFields mapped = fieldMapper.map(text, schema);
        return new ExtractionResult(mapped.fields(), mapped.confidence(), text, costTier().costUnits());
    }

    @Override
    public boolean supports(DocumentKind kind) {
        return kind == DocumentKind.PDF || kind == DocumentKind.IMAGE;
    }

    @Override
    public Optional<String> notConfiguredReason() {
        return ocr.notConfiguredReason();
    }

    @Override
    public CostTier costTier() {
        return CostTier.OCR;
    }

    @Override
    public String name() {
        return "ocr";
    }
}
