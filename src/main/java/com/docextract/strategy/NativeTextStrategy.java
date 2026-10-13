package com.docextract.strategy;

import com.docextract.document.Document;
import com.docextract.document.DocumentKind;
import com.docextract.strategy.mapping.FieldMapper;
import com.docextract.strategy.text.TextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Cheapest tier: the document's own text (PDFBox text layer for PDFs, Tika for Office/text formats). */
@Component
public class NativeTextStrategy implements ExtractionStrategy {

    private final TextExtractor textExtractor;
    private final FieldMapper fieldMapper;

    public NativeTextStrategy(TextExtractor textExtractor, FieldMapper fieldMapper) {
        this.textExtractor = textExtractor;
        this.fieldMapper = fieldMapper;
    }

    @Override
    public ExtractionResult extract(Document document, JsonNode schema) {
        String text = textExtractor.extractText(document);
        FieldMapper.MappedFields mapped = fieldMapper.map(text, schema);
        return new ExtractionResult(mapped.fields(), mapped.confidence(), text, costTier().costUnits());
    }

    /** Images have no text to read; they need OCR or vision. */
    @Override
    public boolean supports(DocumentKind kind) {
        return kind != DocumentKind.IMAGE;
    }

    @Override
    public CostTier costTier() {
        return CostTier.NATIVE_TEXT;
    }

    @Override
    public String name() {
        return "native-text";
    }
}
