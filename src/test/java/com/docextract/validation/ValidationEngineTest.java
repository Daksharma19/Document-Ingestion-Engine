package com.docextract.validation;

import com.docextract.validation.expr.RuleExpression;
import com.docextract.validation.rules.DatesValidRule;
import com.docextract.validation.rules.LineItemsSumRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationEngineTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2025-06-15T12:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();
    private final ValidationEngine engine =
            new ValidationEngine(List.of(new DatesValidRule(FIXED), new LineItemsSumRule()));

    private static final String INVOICE_SCHEMA = """
            {"type":"object",
             "required":["invoiceNumber","total"],
             "properties":{
               "invoiceNumber":{"type":"string"},
               "invoiceDate":{"type":"string","format":"date"},
               "tax":{"type":"number"},
               "total":{"type":"number","minimum":0},
               "lineItems":{"type":"array","items":{"type":"object",
                  "properties":{"description":{"type":"string"},"amount":{"type":"number"}}}}}}""";

    private ValidationReport validate(String fields) throws Exception {
        return engine.validate(json.readTree(fields), json.readTree(INVOICE_SCHEMA));
    }

    private static List<String> rules(ValidationReport r) {
        return r.violations().stream().map(Violation::rule).toList();
    }

    @Test
    void validInvoicePasses() throws Exception {
        var report = validate("""
                {"invoiceNumber":"INV-1","invoiceDate":"2024-03-12","tax":18,"total":118,
                 "lineItems":[{"description":"a","amount":60},{"description":"b","amount":40}]}""");
        assertThat(report.isValid()).as(report.violations().toString()).isTrue();
    }

    @Test
    void missingRequiredFieldIsReported() throws Exception {
        var report = validate("{\"total\":10}");
        assertThat(report.violations()).singleElement()
                .satisfies(v -> {
                    assertThat(v.rule()).isEqualTo(ValidationEngine.SCHEMA_RULE);
                    assertThat(v.message()).contains("invoiceNumber");
                });
    }

    @Test
    void wrongTypeReportsTheField() throws Exception {
        var report = validate("{\"invoiceNumber\":\"X\",\"total\":\"ten\"}");
        assertThat(report.violations()).singleElement()
                .satisfies(v -> assertThat(v.field()).isEqualTo("total"));
    }

    @Test
    void nullExtractedValueFailsSchemaType() throws Exception {
        var report = validate("{\"invoiceNumber\":\"X\",\"total\":null}");
        assertThat(rules(report)).containsExactly(ValidationEngine.SCHEMA_RULE);
    }

    @Test
    void lineItemsNotSummingToTotalIsReported() throws Exception {
        var report = validate("""
                {"invoiceNumber":"X","total":100,"lineItems":[{"amount":60},{"amount":30}]}""");
        assertThat(report.violations()).singleElement().satisfies(v -> {
            assertThat(v.rule()).isEqualTo("line-items-sum");
            assertThat(v.field()).isEqualTo("total");
            assertThat(v.message()).contains("90").contains("100");
        });
    }

    @Test
    void lineItemsWithinOneCentTolerancePass() throws Exception {
        var report = validate("""
                {"invoiceNumber":"X","total":0.3,"lineItems":[{"amount":0.1},{"amount":0.2}]}""");
        assertThat(report.isValid()).isTrue();
    }

    @Test
    void lineItemsCompareAgainstSubtotalWhenPresent() {
        var fields = json.createObjectNode().put("subtotal", 90).put("total", 999);
        fields.putArray("lineItems").addObject().put("amount", 90);
        assertThat(new LineItemsSumRule().check(fields, json.createObjectNode())).isEmpty();
    }

    @Test
    void futureDateIsReported() throws Exception {
        var report = validate("{\"invoiceNumber\":\"X\",\"total\":1,\"invoiceDate\":\"2025-06-16\"}");
        assertThat(report.violations()).singleElement().satisfies(v -> {
            assertThat(v.rule()).isEqualTo("dates-valid");
            assertThat(v.message()).contains("future");
        });
    }

    @Test
    void todayIsNotFuture() throws Exception {
        assertThat(validate("{\"invoiceNumber\":\"X\",\"total\":1,\"invoiceDate\":\"2025-06-15\"}").isValid()).isTrue();
    }

    @Test
    void impossibleDateIsReported() throws Exception {
        var report = validate("{\"invoiceNumber\":\"X\",\"total\":1,\"invoiceDate\":\"2024-02-30\"}");
        assertThat(rules(report)).contains("dates-valid");
    }

    @Test
    void rulesSkipWhenTheirFieldsAreAbsent() throws Exception {
        JsonNode schema = json.readTree("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
        assertThat(engine.validate(json.readTree("{\"name\":\"Bob\"}"), schema).isValid()).isTrue();
    }

    @Test
    void failingExpressionRuleImplicatesEveryReferencedField() throws Exception {
        JsonNode schema = json.readTree("""
                {"type":"object","x-rules":["sum(lineItems[].amount) == subtotal"],
                 "properties":{"invoiceNumber":{"type":"string"},"subtotal":{"type":"number"},
                   "lineItems":{"type":"array"}}}""");
        var report = engine.validate(json.readTree("""
                {"invoiceNumber":"X","subtotal":100,"lineItems":[{"amount":60},{"amount":30}]}"""), schema);
        // The built-in line-items-sum bean catches the same mismatch independently.
        assertThat(rules(report)).containsExactlyInAnyOrder(ValidationEngine.EXPRESSION_RULE, "line-items-sum");
        assertThat(report.violations()).filteredOn(v -> v.rule().equals(ValidationEngine.EXPRESSION_RULE))
                .singleElement().satisfies(v -> {
            assertThat(v.relatedFields()).containsExactly("lineItems", "subtotal");
            assertThat(v.message()).contains("left=90").contains("right=100");
        });
        assertThat(report.fieldPassed(schema))
                .containsEntry("invoiceNumber", true)
                .containsEntry("subtotal", false)
                .containsEntry("lineItems", false);
        assertThat(report.ruleResults()).singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo(RuleResult.Outcome.FAILED));
    }

    @Test
    void requestRulesRunAlongsideSchemaRulesAndSkipWhenFieldsAbsent() throws Exception {
        var report = engine.validate(json.readTree("{\"invoiceNumber\":\"X\",\"total\":10}"), json.readTree(INVOICE_SCHEMA),
                List.of(RuleExpression.parse("total > 0"), RuleExpression.parse("tax < total")));
        assertThat(report.ruleResults()).extracting(RuleResult::outcome)
                .containsExactly(RuleResult.Outcome.PASSED, RuleResult.Outcome.SKIPPED);
        assertThat(report.isValid()).isTrue();
    }

    @Test
    void formatsAreAsserted() throws Exception {
        JsonNode schema = json.readTree("{\"type\":\"object\",\"properties\":{\"email\":{\"type\":\"string\",\"format\":\"email\"}}}");
        assertThat(engine.validate(json.readTree("{\"email\":\"not-an-email\"}"), schema).violations())
                .singleElement().satisfies(v -> assertThat(v.field()).isEqualTo("email"));
        assertThat(engine.validate(json.readTree("{\"email\":\"a@b.com\"}"), schema).isValid()).isTrue();
    }

    @Test
    void perFieldResultMarksWrongTypeAndMissingRequired() throws Exception {
        JsonNode schema = json.readTree(INVOICE_SCHEMA);
        var report = engine.validateExtraction(json.readTree("{\"invoiceNumber\":null,\"total\":\"ten\",\"tax\":null}"),
                schema, List.of());
        assertThat(report.fieldPassed(schema))
                .containsEntry("invoiceNumber", false) // required but not found
                .containsEntry("total", false)         // wrong type
                .containsEntry("tax", true)            // optional and not found: absence is fine
                .containsEntry("invoiceDate", true);
    }

    @Test
    void multipleViolationsAreAllReported() throws Exception {
        var report = validate("""
                {"total":-5,"invoiceDate":"2099-01-01","lineItems":[{"amount":1}]}""");
        assertThat(rules(report)).contains(ValidationEngine.SCHEMA_RULE, "dates-valid", "line-items-sum");
        assertThat(report.violations()).hasSizeGreaterThanOrEqualTo(4);
    }
}
