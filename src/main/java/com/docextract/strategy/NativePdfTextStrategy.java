package com.docextract.strategy;

import com.docextract.document.Document;
import com.docextract.strategy.mapping.FieldMapper;
import com.docextract.strategy.text.TextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class NativePdfTextStrategy implements ExtractionStrategy {

    private final TextExtractor textExtractor;
    private final FieldMapper fieldMapper;

    public NativePdfTextStrategy(TextExtractor textExtractor, FieldMapper fieldMapper) {
        this.textExtractor = textExtractor;
        this.fieldMapper = fieldMapper;
    }

    @Override
    public ExtractionResult extract(Document document, JsonNode schema) {
        String text = textExtractor.extractText(document);
        FieldMapper.MappedFields mapped = fieldMapper.map(text, schema);
        return new ExtractionResult(mapped.fields(), mapped.confidence(), text, costTier().costUnits());
    }

    @Override
    public CostTier costTier() {
        return CostTier.NATIVE_TEXT;
    }

    @Override
    public String name() {
        return "native-pdf-text";
    }
}
