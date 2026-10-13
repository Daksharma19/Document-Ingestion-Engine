package com.docextract.eval;

import com.docextract.eval.corpus.LabeledDocument;
import com.docextract.strategy.ExtractionResult;
import com.docextract.strategy.ExtractionStrategy;
import com.docextract.strategy.TierNotConfiguredException;
import com.docextract.strategy.UnsupportedDocumentException;
import com.docextract.validation.ValidationEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs one strategy over a labelled corpus, validating and scoring every document. Documents the tier
 * cannot read at all (e.g. OCR on a DOCX), or a tier that is not configured, are recorded as skipped and
 * kept out of the accuracy and cost figures rather than scored as zero.
 */
public class Evaluator {

    private final ValidationEngine validationEngine;
    private final ObjectMapper json;

    public Evaluator(ValidationEngine validationEngine, ObjectMapper json) {
        this.validationEngine = validationEngine;
        this.json = json;
    }

    public EvaluationReport evaluate(ExtractionStrategy strategy, List<LabeledDocument> corpus) {
        List<DocumentScore> scores = new ArrayList<>();
        List<EvaluationReport.Skipped> skipped = new ArrayList<>();
        for (LabeledDocument doc : corpus) {
            try {
                scores.add(evaluateOne(strategy, doc));
            } catch (UnsupportedDocumentException | TierNotConfiguredException e) {
                skipped.add(new EvaluationReport.Skipped(doc.name(), e.getMessage()));
            }
        }
        return new EvaluationReport(strategy.name(), scores, skipped);
    }

    private DocumentScore evaluateOne(ExtractionStrategy strategy, LabeledDocument doc) {
        try {
            ExtractionResult result = strategy.extract(doc.document(), doc.schema());
            JsonNode predicted = json.valueToTree(result.fields());
            int violations = validationEngine.validateExtraction(predicted, doc.schema(), List.of()).violations().size();
            return new DocumentScore(doc.name(), strategy.name(),
                    Scorer.scoreFields(doc.expected(), predicted, result.confidence()),
                    result.costUnits(), violations, null);
        } catch (UnsupportedDocumentException | TierNotConfiguredException e) {
            throw e;
        } catch (RuntimeException e) {
            // A failed extraction still costs money: charge the tier's cost and score every field as missing.
            return new DocumentScore(doc.name(), strategy.name(),
                    Scorer.scoreFields(doc.expected(), null, null),
                    strategy.costTier().costUnits(), 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
