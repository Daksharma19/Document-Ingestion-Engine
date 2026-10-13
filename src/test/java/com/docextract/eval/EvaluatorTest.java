package com.docextract.eval;

import com.docextract.document.Document;
import com.docextract.eval.corpus.LabeledDocument;
import com.docextract.strategy.CostTier;
import com.docextract.strategy.ExtractionResult;
import com.docextract.strategy.ExtractionStrategy;
import com.docextract.validation.ValidationEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluatorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final Evaluator evaluator = new Evaluator(new ValidationEngine(List.of()), json);

    private LabeledDocument labelled(String name, String expected) throws Exception {
        JsonNode schema = json.readTree("{\"type\":\"object\",\"properties\":{\"total\":{\"type\":\"number\"}}}");
        return new LabeledDocument(name, new Document(name + ".txt", "text/plain", new byte[]{1}),
                schema, json.readTree(expected));
    }

    /** Fake strategy returning a fixed total, or throwing for a document named "bad". */
    private static ExtractionStrategy fixed(Object total) {
        return new ExtractionStrategy() {
            @Override
            public ExtractionResult extract(Document document, JsonNode schema) {
                if (document.filename().startsWith("bad")) {
                    throw new IllegalStateException("corrupt");
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("total", total);
                return new ExtractionResult(fields, Map.of("total", 0.9), "", costTier().costUnits());
            }

            @Override
            public CostTier costTier() {
                return CostTier.OCR;
            }

            @Override
            public String name() {
                return "fake";
            }
        };
    }

    @Test
    void scoresEachDocumentAndSumsCost() throws Exception {
        var report = evaluator.evaluate(fixed(10), List.of(labelled("a", "{\"total\":10}"), labelled("b", "{\"total\":99}")));
        assertThat(report.documents()).extracting(DocumentScore::accuracy).containsExactly(1.0, 0.0);
        assertThat(report.totalCostUnits()).isEqualTo(2 * CostTier.OCR.costUnits());
        assertThat(report.validButWrongCount()).isEqualTo(1);
    }

    @Test
    void failingDocumentIsRecordedChargedAndDoesNotAbortTheRun() throws Exception {
        var report = evaluator.evaluate(fixed(10), List.of(labelled("bad", "{\"total\":10}"), labelled("a", "{\"total\":10}")));
        DocumentScore bad = report.documents().getFirst();
        assertThat(bad.error()).contains("corrupt");
        assertThat(bad.count(FieldOutcome.MISSING)).isEqualTo(1);
        assertThat(bad.costUnits()).isEqualTo(CostTier.OCR.costUnits());
        assertThat(report.documents().get(1).exactMatch()).isTrue();
    }

    @Test
    void nullPredictionIsMissingAndFailsValidationOnlyWhenRequired() throws Exception {
        var optional = evaluator.evaluate(fixed(null), List.of(labelled("a", "{\"total\":10}"))).documents().getFirst();
        assertThat(optional.count(FieldOutcome.MISSING)).isEqualTo(1);
        assertThat(optional.passedValidation()).as("not found is absence, not a type error").isTrue();

        JsonNode required = json.readTree("{\"type\":\"object\",\"required\":[\"total\"],\"properties\":{\"total\":{\"type\":\"number\"}}}");
        var doc = new LabeledDocument("r", new Document("r.txt", "text/plain", new byte[]{1}), required, json.readTree("{\"total\":10}"));
        assertThat(evaluator.evaluate(fixed(null), List.of(doc)).documents().getFirst().passedValidation()).isFalse();
    }

    @Test
    void unsupportedDocumentIsSkippedNotScored() throws Exception {
        ExtractionStrategy unsupported = new ExtractionStrategy() {
            @Override
            public ExtractionResult extract(Document document, JsonNode schema) {
                throw new com.docextract.strategy.UnsupportedDocumentException("no page image");
            }

            @Override
            public CostTier costTier() {
                return CostTier.OCR;
            }

            @Override
            public String name() {
                return "ocr-like";
            }
        };
        var report = evaluator.evaluate(unsupported, List.of(labelled("a", "{\"total\":10}")));
        assertThat(report.documents()).isEmpty();
        assertThat(report.skipped()).singleElement().satisfies(s -> assertThat(s.reason()).contains("no page image"));
        assertThat(report.totalCostUnits()).isZero();
    }

    @Test
    void csvEscapesAndWritesOneRowPerField(@TempDir Path dir) throws Exception {
        assertThat(ReportWriter.csvCell("a,b")).isEqualTo("\"a,b\"");
        assertThat(ReportWriter.csvCell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(ReportWriter.csvCell("plain")).isEqualTo("plain");

        var report = evaluator.evaluate(fixed(10), List.of(labelled("a", "{\"total\":10}")));
        ReportWriter.writeCsv(List.of(report), dir.resolve("f.csv"), dir.resolve("d.csv"));
        assertThat(Files.readAllLines(dir.resolve("f.csv")))
                .containsExactly("strategy,document,field,outcome,expected,predicted,confidence",
                        "fake,a,total,CORRECT,10,10,0.90");
        assertThat(Files.readAllLines(dir.resolve("d.csv"))).hasSize(2);
    }

    @Test
    void csvWritesNumbersInPlainNotation(@TempDir Path dir) throws Exception {
        var report = evaluator.evaluate(fixed(new java.math.BigDecimal("1.4E+2")), List.of(labelled("a", "{\"total\":140}")));
        ReportWriter.writeCsv(List.of(report), dir.resolve("f.csv"), dir.resolve("d.csv"));
        assertThat(Files.readAllLines(dir.resolve("f.csv")).get(1)).isEqualTo("fake,a,total,CORRECT,140,140,0.90");
    }
}
