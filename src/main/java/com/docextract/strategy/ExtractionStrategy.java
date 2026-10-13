package com.docextract.strategy;

import com.docextract.document.Document;
import com.docextract.document.DocumentKind;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

public interface ExtractionStrategy {

    /**
     * @throws TierNotConfiguredException when {@link #notConfiguredReason()} is present
     * @throws UnsupportedDocumentException when the document's kind is not {@link #supports supported}
     */
    ExtractionResult extract(Document document, JsonNode schema);

    CostTier costTier();

    String name();

    /** Whether this tier can read documents of this kind at all. */
    default boolean supports(DocumentKind kind) {
        return true;
    }

    /** Empty when the tier is ready; otherwise why not (missing API key, missing OCR data, ...). */
    default Optional<String> notConfiguredReason() {
        return Optional.empty();
    }
}
