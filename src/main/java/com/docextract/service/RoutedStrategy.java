package com.docextract.service;

import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.strategy.CostTier;
import com.docextract.strategy.ExtractionResult;
import com.docextract.strategy.ExtractionStrategy;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The API's automatic routing wrapped as a strategy, so the evaluation harness can score it like a tier.
 * Deliberately not a Spring bean: it would otherwise appear as a selectable tier of itself.
 */
public class RoutedStrategy implements ExtractionStrategy {

    private final TierRouter router;
    private final DocumentTypeDetector detector;

    public RoutedStrategy(TierRouter router, DocumentTypeDetector detector) {
        this.router = router;
        this.detector = detector;
    }

    @Override
    public ExtractionResult extract(Document document, JsonNode schema) {
        return router.route(document, detector.detect(document)).strategy().extract(document, schema);
    }

    /** Only used for ordering and for charging failures; actual cost comes from the tier that ran. */
    @Override
    public CostTier costTier() {
        return CostTier.NATIVE_TEXT;
    }

    @Override
    public String name() {
        return "auto-routed";
    }
}
