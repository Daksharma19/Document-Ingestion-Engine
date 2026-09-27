package com.docextract.eval;

import com.docextract.eval.corpus.LabeledDocument;
import com.docextract.strategy.ExtractionResult;
import com.docextract.strategy.ExtractionStrategy;
import com.docextract.validation.ValidationEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Runs one strategy over a labelled corpus, validating and scoring every document. */
public class Evaluator {

    private final ValidationEngine validationEngine;
    private final ObjectMapper json;

    public Evaluator(ValidationEngine validationEngine, ObjectMapper json) {
        this.validationEngine = validationEngine;
        this.json = json;
    }

    public EvaluationReport evaluate(ExtractionStrategy strategy, List<LabeledDocument> corpus) {
        List<DocumentScore> scores = new ArrayList<>();
        for (LabeledDocument doc : corpus) {
            scores.add(evaluateOne(strategy, doc));
        }
        return new EvaluationReport(strategy.name(), scores);
    }

    private DocumentScore evaluateOne(ExtractionStrategy strategy, LabeledDocument doc) {
        try {
            ExtractionResult result = strategy.extract(doc.document(), doc.schema());
            JsonNode predicted = json.valueToTree(result.fields());
            int violations = validationEngine.validate(predicted, doc.schema()).violations().size();
            return new DocumentScore(doc.name(), strategy.name(),
                    Scorer.scoreFields(doc.expected(), predicted, result.confidence()),
                    result.costUnits(), violations, null);
        } catch (RuntimeException e) {
            // A failed extraction still costs money: charge the tier's cost and score every field as missing.
            return new DocumentScore(doc.name(), strategy.name(),
                    Scorer.scoreFields(doc.expected(), null, null),
                    strategy.costTier().costUnits(), 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
