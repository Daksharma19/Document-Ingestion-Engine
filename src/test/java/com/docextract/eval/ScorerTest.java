package com.docextract.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScorerTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode j(String s) throws Exception {
        return json.readTree(s);
    }

    private FieldOutcome outcome(String expected, String predicted) throws Exception {
        return Scorer.outcome(j(expected), j(predicted));
    }

    @Test
    void numbersCompareByValueNotRepresentation() throws Exception {
        assertThat(outcome("165.20", "165.2")).isEqualTo(FieldOutcome.CORRECT);
        assertThat(outcome("3", "3.00")).isEqualTo(FieldOutcome.CORRECT);
        assertThat(outcome("23.47", "3")).isEqualTo(FieldOutcome.WRONG);
    }

    @Test
    void stringsIgnoreCaseAndSurroundingOrRepeatedWhitespace() throws Exception {
        assertThat(outcome("\"ACME  Ltd\"", "\" acme ltd \"")).isEqualTo(FieldOutcome.CORRECT);
        assertThat(outcome("\"ACME Ltd\"", "\"ACME Limited\"")).isEqualTo(FieldOutcome.WRONG);
    }

    @Test
    void numberAndItsStringFormAreNotEqual() throws Exception {
        assertThat(outcome("42", "\"42\"")).isEqualTo(FieldOutcome.WRONG);
    }

    @Test
    void nullOrAbsentPredictionIsMissingNotWrong() throws Exception {
        assertThat(outcome("\"x\"", "null")).isEqualTo(FieldOutcome.MISSING);
        assertThat(Scorer.outcome(j("\"x\""), json.missingNode())).isEqualTo(FieldOutcome.MISSING);
    }

    @Test
    void expectedNullAndPredictedNullIsCorrect() throws Exception {
        assertThat(outcome("null", "null")).isEqualTo(FieldOutcome.CORRECT);
        assertThat(outcome("null", "\"hallucinated\"")).isEqualTo(FieldOutcome.WRONG);
    }

    @Test
    void arraysMustMatchElementWiseInOrder() throws Exception {
        String expected = "[{\"d\":\"a\",\"amount\":1.0},{\"d\":\"b\",\"amount\":2}]";
        assertThat(outcome(expected, "[{\"d\":\"A\",\"amount\":1},{\"d\":\"b\",\"amount\":2.00}]")).isEqualTo(FieldOutcome.CORRECT);
        assertThat(outcome(expected, "[{\"d\":\"b\",\"amount\":2},{\"d\":\"a\",\"amount\":1}]")).isEqualTo(FieldOutcome.WRONG);
        assertThat(outcome(expected, "[{\"d\":\"a\",\"amount\":1}]")).isEqualTo(FieldOutcome.WRONG);
    }

    @Test
    void objectsIgnoreExtraPredictedKeysButRequireLabelledOnes() throws Exception {
        assertThat(outcome("{\"a\":1}", "{\"a\":1,\"extra\":true}")).isEqualTo(FieldOutcome.CORRECT);
        assertThat(outcome("{\"a\":1,\"b\":2}", "{\"a\":1}")).isEqualTo(FieldOutcome.WRONG);
    }

    @Test
    void scoreFieldsOnlyScoresLabelledFieldsAndCarriesConfidence() throws Exception {
        List<FieldScore> scores = Scorer.scoreFields(
                j("{\"total\":10,\"date\":\"2024-01-01\"}"),
                j("{\"total\":10,\"date\":null,\"unlabelled\":\"ignored\"}"),
                Map.of("total", 0.8));
        assertThat(scores).extracting(FieldScore::field).containsExactly("total", "date");
        assertThat(scores).extracting(FieldScore::outcome).containsExactly(FieldOutcome.CORRECT, FieldOutcome.MISSING);
        assertThat(scores.get(0).confidence()).isEqualTo(0.8);
        assertThat(scores.get(1).confidence()).isEqualTo(0.0);
    }

    @Test
    void nullPredictionObjectScoresEverythingMissing() throws Exception {
        var scores = Scorer.scoreFields(j("{\"a\":1,\"b\":\"x\"}"), null, null);
        assertThat(scores).extracting(FieldScore::outcome).containsOnly(FieldOutcome.MISSING);
    }
}
