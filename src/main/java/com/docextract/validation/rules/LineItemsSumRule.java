package com.docextract.validation.rules;

import com.docextract.validation.BusinessRule;
import com.docextract.validation.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * lineItems[].amount must reconcile with the document totals:
 * with a subtotal, items == subtotal; with a tax figure, items + tax == total. With neither, item amounts may be
 * pre-tax (tax given as CGST/SGST rows, round-off...), so only items <em>exceeding</em> the total (and no discount)
 * is a contradiction.
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
            BigDecimal total = fields.get("total").decimalValue();
            if (fields.path("tax").isNumber()) {
                return compare(sum.add(fields.get("tax").decimalValue()), total, "total", "line items + tax");
            }
            if (sum.subtract(total).compareTo(TOLERANCE) > 0 && !fields.path("discount").isNumber()) {
                return List.of(new Violation("total", name(),
                        "line items sum is " + sum.toPlainString() + ", more than the total " + total.toPlainString()));
            }
            return List.of();
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
