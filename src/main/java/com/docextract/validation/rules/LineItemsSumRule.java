package com.docextract.validation.rules;

import com.docextract.validation.BusinessRule;
import com.docextract.validation.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * lineItems[].amount must reconcile with the document totals:
 * with a subtotal, items == subtotal; otherwise items + tax (if any) == total.
 */
@Component
public class LineItemsSumRule implements BusinessRule {

    static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    @Override
    public String name() {
        return "line-items-sum";
    }

    @Override
    public List<Violation> check(JsonNode fields, JsonNode schema) {
        JsonNode items = fields.path("lineItems");
        if (!items.isArray() || items.isEmpty()) {
            return List.of();
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode item : items) {
            if (!item.path("amount").isNumber()) {
                return List.of();
            }
            sum = sum.add(item.get("amount").decimalValue());
        }

        if (fields.path("subtotal").isNumber()) {
            return compare(sum, fields.get("subtotal").decimalValue(), "subtotal", "line items sum");
        }
        if (fields.path("total").isNumber()) {
            BigDecimal expected = sum;
            String what = "line items sum";
            if (fields.path("tax").isNumber()) {
                expected = sum.add(fields.get("tax").decimalValue());
                what = "line items + tax";
            }
            return compare(expected, fields.get("total").decimalValue(), "total", what);
        }
        return List.of();
    }

    private List<Violation> compare(BigDecimal computed, BigDecimal stated, String field, String what) {
        if (computed.subtract(stated).abs().compareTo(TOLERANCE) <= 0) {
            return List.of();
        }
        return List.of(new Violation(field, name(),
                what + " is " + computed.toPlainString() + " but " + field + " is " + stated.toPlainString()));
    }
}
