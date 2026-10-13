package com.docextract.validation.expr;

import com.docextract.validation.expr.RuleExpression.MissingValueException;
import com.docextract.validation.expr.RuleExpression.RuleSyntaxException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleExpressionTest {

    private final ObjectMapper json = new ObjectMapper();

    private final JsonNode invoice = read("""
            {"invoiceDate":"2024-03-12","dueDate":"2024-04-11","subtotal":140.00,"tax":25.20,"total":165.20,
             "currency":"GBP","paid":false,
             "line_items":[{"description":"Paper","amount":45.00,"qty":2},
                           {"description":"Toner","amount":80.00,"qty":1},
                           {"description":"Delivery","amount":15.00,"qty":1}]}""");

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private boolean holds(String rule) {
        return RuleExpression.parse(rule).test(invoice);
    }

    @Test
    void sumOverArrayPathMatchesSubtotal() {
        assertThat(holds("sum(line_items[].amount) == subtotal")).isTrue();
        assertThat(holds("sum(line_items[].amount) == total")).isFalse();
    }

    @Test
    void arithmeticAndPrecedence() {
        assertThat(holds("subtotal + tax == total")).isTrue();
        assertThat(holds("subtotal * 0.18 == tax")).isTrue();
        assertThat(holds("total - tax - subtotal == 0")).isTrue();
        assertThat(holds("2 + 3 * 4 == 14")).isTrue();
        assertThat(holds("(2 + 3) * 4 == 20")).isTrue();
        assertThat(holds("-tax < 0")).isTrue();
    }

    @Test
    void moneyEqualityToleratesOneCent() {
        assertThat(holds("total == 165.21")).isTrue();
        assertThat(holds("total == 165.22")).isFalse();
        assertThat(holds("total != 165.30")).isTrue();
    }

    @Test
    void aggregateFunctions() {
        assertThat(holds("count(line_items[]) == 3")).isTrue();
        assertThat(holds("max(line_items[].amount) == 80")).isTrue();
        assertThat(holds("min(line_items[].amount) == 15")).isTrue();
        assertThat(holds("round(avg(line_items[].amount), 2) == 46.67")).isTrue();
        assertThat(holds("abs(subtotal - total) == 25.20")).isTrue();
        assertThat(holds("line_items[1].amount == 80")).isTrue();
    }

    @Test
    void isoDatesCompareAsStringsAndBooleansCombine() {
        assertThat(holds("dueDate >= invoiceDate")).isTrue();
        assertThat(holds("dueDate < invoiceDate")).isFalse();
        assertThat(holds("currency == 'gbp' && total > 0")).isTrue();
        assertThat(holds("paid == true || total > 1000")).isFalse();
        assertThat(holds("!(total > 1000)")).isTrue();
    }

    @Test
    void existsDoesNotSkip() {
        assertThat(holds("exists(subtotal)")).isTrue();
        assertThat(holds("exists(discount)")).isFalse();
        assertThat(holds("!exists(discount) || discount >= 0")).isTrue();
    }

    @Test
    void absentValueThrowsMissingSoTheRuleIsSkipped() {
        var rule = RuleExpression.parse("subtotal + discount == total");
        assertThatThrownBy(() -> rule.test(invoice)).isInstanceOf(MissingValueException.class)
                .hasMessageContaining("discount");
        var nullValue = read("{\"subtotal\":1,\"total\":null}");
        assertThatThrownBy(() -> RuleExpression.parse("subtotal == total").test(nullValue))
                .isInstanceOf(MissingValueException.class);
    }

    @Test
    void emptyArrayIsARealEmptyList() {
        assertThat(RuleExpression.parse("sum(items[].amount) == 0").test(read("{\"items\":[]}"))).isTrue();
    }

    @Test
    void reportsReferencedTopLevelFieldsInOrder() {
        assertThat(RuleExpression.parse("sum(line_items[].amount) + tax == total").referencedFields())
                .containsExactly("line_items", "tax", "total");
    }

    @Test
    void explainShowsBothSides() {
        assertThat(RuleExpression.parse("sum(line_items[].amount) == total").explain(invoice))
                .isEqualTo("left=140, right=165.2");
    }

    @Test
    void evaluatesToRawValues() {
        assertThat(RuleExpression.parse("sum(line_items[].qty)").evaluate(invoice)).isEqualTo(new BigDecimal("4"));
    }

    @Test
    void syntaxErrorsAreRejectedAtParseTime() {
        assertThatThrownBy(() -> RuleExpression.parse("total ==")).isInstanceOf(RuleSyntaxException.class);
        assertThatThrownBy(() -> RuleExpression.parse("total = 5")).isInstanceOf(RuleSyntaxException.class);
        assertThatThrownBy(() -> RuleExpression.parse("eval(total)")).isInstanceOf(RuleSyntaxException.class)
                .hasMessageContaining("unknown function");
        assertThatThrownBy(() -> RuleExpression.parse("(total > 1")).isInstanceOf(RuleSyntaxException.class);
        assertThatThrownBy(() -> RuleExpression.parse("")).isInstanceOf(RuleSyntaxException.class);
    }

    @Test
    void nonBooleanRuleIsATypeError() {
        assertThatThrownBy(() -> RuleExpression.parse("total + 1").test(invoice)).isInstanceOf(RuleSyntaxException.class);
        assertThatThrownBy(() -> RuleExpression.parse("sum(total) == 1").test(invoice))
                .isInstanceOf(RuleSyntaxException.class).hasMessageContaining("list");
    }
}
