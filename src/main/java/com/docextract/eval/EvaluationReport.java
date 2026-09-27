package com.docextract.eval;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Aggregate metrics for one strategy over a corpus. */
public record EvaluationReport(String strategy, List<DocumentScore> documents) {

    public record FieldStats(int total, int correct, int wrong, int missing) {
        public double accuracy() {
            return total == 0 ? 0.0 : (double) correct / total;
        }
    }

    /** Accuracy grouped by field name across all documents that label that field. */
    public Map<String, FieldStats> perField() {
        Map<String, int[]> counts = new TreeMap<>();
        for (DocumentScore d : documents) {
            for (FieldScore f : d.fields()) {
                int[] c = counts.computeIfAbsent(f.field(), k -> new int[4]);
                c[0]++;
                c[1 + f.outcome().ordinal()]++;
            }
        }
        Map<String, FieldStats> out = new TreeMap<>();
        counts.forEach((k, c) -> out.put(k, new FieldStats(c[0], c[1], c[2], c[3])));
        return out;
    }

    /** Micro-average: correct fields / labelled fields, pooled over the whole corpus. */
    public double fieldAccuracy() {
        long total = documents.stream().mapToLong(d -> d.fields().size()).sum();
        long correct = documents.stream().mapToLong(d -> d.count(FieldOutcome.CORRECT)).sum();
        return total == 0 ? 0.0 : (double) correct / total;
    }

    /** Macro-average: mean of per-document accuracies, so each document counts equally. */
    public double meanDocumentAccuracy() {
        return documents.stream().mapToDouble(DocumentScore::accuracy).average().orElse(0.0);
    }

    public double exactMatchRate() {
        return documents.isEmpty() ? 0.0
                : (double) documents.stream().filter(DocumentScore::exactMatch).count() / documents.size();
    }

    public int totalCostUnits() {
        return documents.stream().mapToInt(DocumentScore::costUnits).sum();
    }

    public double meanCostPerDocument() {
        return documents.isEmpty() ? 0.0 : (double) totalCostUnits() / documents.size();
    }

    public long validButWrongCount() {
        return documents.stream().filter(DocumentScore::validButWrong).count();
    }

    public long errorCount() {
        return documents.stream().filter(d -> d.error() != null).count();
    }
}
