package com.docextract.strategy;

import com.docextract.document.Document;
import com.fasterxml.jackson.databind.JsonNode;

public interface ExtractionStrategy {

    ExtractionResult extract(Document document, JsonNode schema);

    CostTier costTier();

    String name();
}
