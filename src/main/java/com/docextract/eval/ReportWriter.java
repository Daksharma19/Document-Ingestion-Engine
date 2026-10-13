package com.docextract.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Console tables for humans, CSV (one row per strategy/document/field) for analysis. */
public final class ReportWriter {

    private ReportWriter() {
    }

    public static void printConsole(EvaluationReport r, PrintStream out) {
        out.println();
        out.println("=== Strategy: " + r.strategy() + " ===");
        out.println();
        out.println("Per-document");
        table(out, List.of("document", "accuracy", "correct", "wrong", "missing", "valid", "cost", "note"),
                r.documents().stream().map(d -> List.of(
                        d.document(), pct(d.accuracy()),
                        String.valueOf(d.count(FieldOutcome.CORRECT)),
                        String.valueOf(d.count(FieldOutcome.WRONG)),
                        String.valueOf(d.count(FieldOutcome.MISSING)),
                        d.passedValidation() ? "yes" : "no (" + d.violationCount() + ")",
                        String.valueOf(d.costUnits()),
                        d.error() != null ? "ERROR " + d.error() : d.validButWrong() ? "VALID BUT WRONG" : ""))
                        .toList());
        out.println();
        out.println("Per-field");
        table(out, List.of("field", "accuracy", "n", "correct", "wrong", "missing"),
                r.perField().entrySet().stream().map(e -> List.of(
                        e.getKey(), pct(e.getValue().accuracy()),
                        String.valueOf(e.getValue().total()), String.valueOf(e.getValue().correct()),
                        String.valueOf(e.getValue().wrong()), String.valueOf(e.getValue().missing())))
                        .toList());
        out.println();
        out.println("Summary");
        table(out, List.of("metric", "value"), List.of(
                List.of("documents", String.valueOf(r.documents().size())),
                List.of("field accuracy (micro)", pct(r.fieldAccuracy())),
                List.of("document accuracy (macro)", pct(r.meanDocumentAccuracy())),
                List.of("exact-match documents", pct(r.exactMatchRate())),
                List.of("valid-but-wrong documents", String.valueOf(r.validButWrongCount())),
                List.of("errors", String.valueOf(r.errorCount())),
                List.of("total cost units", String.valueOf(r.totalCostUnits())),
                List.of("mean cost / document", String.format(Locale.ROOT, "%.2f", r.meanCostPerDocument())),
                List.of("skipped (tier cannot read)", String.valueOf(r.skipped().size()))));
        if (!r.skipped().isEmpty()) {
            out.println();
            out.println("Skipped (not counted in the metrics above)");
            table(out, List.of("document", "reason"),
                    r.skipped().stream().map(s -> List.of(s.document(), s.reason())).toList());
        }
    }

    /** One row per tier, side by side: the accuracy-vs-cost trade-off the router is meant to exploit. */
    public static void printComparison(List<EvaluationReport> reports, Map<String, String> notConfigured, PrintStream out) {
        out.println();
        out.println("=== Tier comparison ===");
        List<List<String>> rows = new ArrayList<>();
        for (EvaluationReport r : reports) {
            rows.add(List.of(r.strategy(), r.documents().size() + "/" + (r.documents().size() + r.skipped().size()),
                    pct(r.fieldAccuracy()), pct(r.meanDocumentAccuracy()), pct(r.exactMatchRate()),
                    String.valueOf(r.validButWrongCount()), String.format(Locale.ROOT, "%.2f", r.meanCostPerDocument()),
                    String.valueOf(r.totalCostUnits())));
        }
        notConfigured.forEach((name, reason) -> rows.add(List.of(name, "-", "not configured", "-", "-", "-", "-", "-")));
        table(out, List.of("strategy", "docs run", "field acc", "doc acc", "exact", "valid-but-wrong",
                "cost/doc", "total cost"), rows);
    }

    public static void writeTierCsv(List<EvaluationReport> reports, Map<String, String> notConfigured, Path tiersCsv) throws IOException {
        List<String> rows = new ArrayList<>();
        rows.add("strategy,status,documents_run,documents_skipped,field_accuracy,document_accuracy,exact_match_rate,valid_but_wrong,errors,total_cost_units,mean_cost_per_document");
        for (EvaluationReport r : reports) {
            rows.add(csvRow(r.strategy(), "ran", String.valueOf(r.documents().size()), String.valueOf(r.skipped().size()),
                    String.format(Locale.ROOT, "%.4f", r.fieldAccuracy()),
                    String.format(Locale.ROOT, "%.4f", r.meanDocumentAccuracy()),
                    String.format(Locale.ROOT, "%.4f", r.exactMatchRate()),
                    String.valueOf(r.validButWrongCount()), String.valueOf(r.errorCount()),
                    String.valueOf(r.totalCostUnits()), String.format(Locale.ROOT, "%.2f", r.meanCostPerDocument())));
        }
        notConfigured.forEach((name, reason) -> rows.add(csvRow(name, "not configured: " + reason, "", "", "", "", "", "", "", "", "")));
        Files.write(tiersCsv, rows);
    }

    public static void writeCsv(List<EvaluationReport> reports, Path fieldsCsv, Path documentsCsv) throws IOException {
        List<String> fieldRows = new ArrayList<>();
        fieldRows.add("strategy,document,field,outcome,expected,predicted,confidence");
        List<String> docRows = new ArrayList<>();
        docRows.add("strategy,document,accuracy,correct,wrong,missing,exact_match,passed_validation,violations,valid_but_wrong,cost_units,error");
        for (EvaluationReport r : reports) {
            for (DocumentScore d : r.documents()) {
                for (FieldScore f : d.fields()) {
                    fieldRows.add(csvRow(r.strategy(), d.document(), f.field(), f.outcome().name(),
                            jsonText(f.expected()), jsonText(f.predicted()),
                            String.format(Locale.ROOT, "%.2f", f.confidence())));
                }
                docRows.add(csvRow(r.strategy(), d.document(),
                        String.format(Locale.ROOT, "%.4f", d.accuracy()),
                        String.valueOf(d.count(FieldOutcome.CORRECT)),
                        String.valueOf(d.count(FieldOutcome.WRONG)),
                        String.valueOf(d.count(FieldOutcome.MISSING)),
                        String.valueOf(d.exactMatch()), String.valueOf(d.passedValidation()),
                        String.valueOf(d.violationCount()), String.valueOf(d.validButWrong()),
                        String.valueOf(d.costUnits()), d.error() == null ? "" : d.error()));
            }
        }
        Files.write(fieldsCsv, fieldRows);
        Files.write(documentsCsv, docRows);
    }

    static String csvRow(String... cells) {
        List<String> escaped = new ArrayList<>();
        for (String c : cells) {
            escaped.add(csvCell(c));
        }
        return String.join(",", escaped);
    }

    /** RFC 4180: quote cells containing comma, quote or newline, doubling inner quotes. */
    static String csvCell(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String jsonText(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        if (n.isNumber()) {
            return n.decimalValue().toPlainString();
        }
        return n.isTextual() ? n.asText() : n.toString();
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    private static void table(PrintStream out, List<String> header, List<List<String>> rows) {
        int[] w = new int[header.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = header.get(i).length();
            for (List<String> row : rows) {
                w[i] = Math.max(w[i], row.get(i).length());
            }
        }
        StringBuilder sep = new StringBuilder("+");
        for (int width : w) {
            sep.append("-".repeat(width + 2)).append('+');
        }
        out.println(sep);
        out.println(line(header, w));
        out.println(sep);
        for (List<String> row : rows) {
            out.println(line(row, w));
        }
        out.println(sep);
    }

    private static String line(List<String> cells, int[] w) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < w.length; i++) {
            sb.append(' ').append(String.format("%-" + w[i] + "s", cells.get(i))).append(" |");
        }
        return sb.toString();
    }
}
