package com.docextract.eval;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.docextract.eval.FieldOutcome.CORRECT;
import static com.docextract.eval.FieldOutcome.MISSING;
import static com.docextract.eval.FieldOutcome.WRONG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvaluationReportTest {

    private static FieldScore f(String name, FieldOutcome o) {
        return new FieldScore(name, JsonNodeFactory.instance.nullNode(), JsonNodeFactory.instance.nullNode(), o, 0);
    }

    private static DocumentScore doc(String name, int cost, int violations, FieldOutcome... outcomes) {
        List<FieldScore> fields = new java.util.ArrayList<>();
        for (int i = 0; i < outcomes.length; i++) {
            fields.add(f("f" + i, outcomes[i]));
        }
        return new DocumentScore(name, "s", fields, cost, violations, null);
    }

    // doc a: 4 fields, 3 correct. doc b: 1 field, 0 correct. Micro = 3/5, macro = (0.75 + 0) / 2.
    private final EvaluationReport report = new EvaluationReport("s", List.of(
            doc("a", 1, 0, CORRECT, CORRECT, CORRECT, WRONG),
            doc("b", 20, 2, MISSING)));

    @Test
    void microAccuracyPoolsAllFields() {
        assertThat(report.fieldAccuracy()).isCloseTo(0.6, within(1e-9));
    }

    @Test
    void macroAccuracyWeightsDocumentsEqually() {
        assertThat(report.meanDocumentAccuracy()).isCloseTo(0.375, within(1e-9));
    }

    @Test
    void perFieldGroupsByNameAcrossDocuments() {
        var stats = report.perField().get("f0");
        assertThat(stats.total()).isEqualTo(2);
        assertThat(stats.correct()).isEqualTo(1);
        assertThat(stats.missing()).isEqualTo(1);
        assertThat(stats.accuracy()).isEqualTo(0.5);
        assertThat(report.perField().get("f3").wrong()).isEqualTo(1);
    }

    @Test
    void costsAreSummedAndAveraged() {
        assertThat(report.totalCostUnits()).isEqualTo(21);
        assertThat(report.meanCostPerDocument()).isEqualTo(10.5);
    }

    @Test
    void exactMatchRequiresEveryFieldCorrect() {
        var r = new EvaluationReport("s", List.of(doc("a", 1, 0, CORRECT, CORRECT), doc("b", 1, 0, CORRECT, WRONG)));
        assertThat(r.exactMatchRate()).isEqualTo(0.5);
    }

    @Test
    void validButWrongCountsDocumentsThatPassValidationYetScoreBadly() {
        // doc a passes validation (0 violations) but has a WRONG field; doc b fails validation.
        assertThat(report.validButWrongCount()).isEqualTo(1);
    }

    @Test
    void erroredDocumentIsNotConsideredValid() {
        var errored = new DocumentScore("e", "s", List.of(f("x", MISSING)), 1, 0, "boom");
        assertThat(errored.passedValidation()).isFalse();
        assertThat(new EvaluationReport("s", List.of(errored)).errorCount()).isEqualTo(1);
    }

    @Test
    void emptyReportDoesNotDivideByZero() {
        var empty = new EvaluationReport("s", List.of());
        assertThat(empty.fieldAccuracy()).isZero();
        assertThat(empty.meanDocumentAccuracy()).isZero();
        assertThat(empty.exactMatchRate()).isZero();
        assertThat(empty.meanCostPerDocument()).isZero();
    }
}
