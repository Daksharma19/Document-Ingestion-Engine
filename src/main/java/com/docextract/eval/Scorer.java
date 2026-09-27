package com.docextract.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

/** Pure scoring logic: compares predictions to labels. No I/O, no Spring. */
public final class Scorer {

    private Scorer() {
    }

    /** Scores only the fields present in {@code expected}; extra predicted fields are ignored. */
    public static List<FieldScore> scoreFields(JsonNode expected, JsonNode predicted, Map<String, Double> confidence) {
        List<FieldScore> out = new ArrayList<>();
        Iterator<Entry<String, JsonNode>> it = expected.fields();
        while (it.hasNext()) {
            Entry<String, JsonNode> e = it.next();
            JsonNode p = predicted == null ? MissingNode.getInstance() : predicted.path(e.getKey());
            out.add(new FieldScore(e.getKey(), e.getValue(), p, outcome(e.getValue(), p),
                    confidence == null ? 0.0 : confidence.getOrDefault(e.getKey(), 0.0)));
        }
        return out;
    }

    public static FieldOutcome outcome(JsonNode expected, JsonNode predicted) {
        boolean predictedAbsent = predicted == null || predicted.isMissingNode() || predicted.isNull();
        if (predictedAbsent) {
            return expected.isNull() ? FieldOutcome.CORRECT : FieldOutcome.MISSING;
        }
        return matches(expected, predicted) ? FieldOutcome.CORRECT : FieldOutcome.WRONG;
    }

    /**
     * Equality after normalisation: numbers by numeric value (165.2 == 165.20), strings trimmed with
     * whitespace collapsed and case ignored, arrays element-wise in order, objects key-wise.
     */
    public static boolean matches(JsonNode expected, JsonNode predicted) {
        if (expected.isNumber() && predicted.isNumber()) {
            return expected.decimalValue().compareTo(predicted.decimalValue()) == 0;
        }
        if (expected.isTextual() && predicted.isTextual()) {
            return normalise(expected.asText()).equals(normalise(predicted.asText()));
        }
        if (expected.isArray() && predicted.isArray()) {
            if (expected.size() != predicted.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!matches(expected.get(i), predicted.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isObject() && predicted.isObject()) {
            Iterator<Entry<String, JsonNode>> it = expected.fields();
            while (it.hasNext()) {
                Entry<String, JsonNode> e = it.next();
                JsonNode p = predicted.path(e.getKey());
                if (outcome(e.getValue(), p) != FieldOutcome.CORRECT) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isNull() || predicted.isNull()) {
            return expected.isNull() && predicted.isNull();
        }
        return expected.equals(predicted);
    }

    static String normalise(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
