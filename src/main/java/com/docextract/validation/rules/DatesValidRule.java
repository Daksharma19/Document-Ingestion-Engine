package com.docextract.validation.rules;

import com.docextract.validation.BusinessRule;
import com.docextract.validation.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Every top-level property declared as format "date" must be a real ISO date. Dates that record the past (issue,
 * invoice, birth...) must not be in the future; dates that point forward (due, expiry, delivery, valid until...) may.
 */
@Component
public class DatesValidRule implements BusinessRule {

    private static final java.util.regex.Pattern FORWARD_LOOKING = java.util.regex.Pattern.compile(
            "(?i).*(due|expir|valid|until|till|deliver|deadline|renew|next|payby|end|ship|eta|arriv|maturity).*");

    private final Clock clock;

    public DatesValidRule(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "dates-valid";
    }

    @Override
    public List<Violation> check(JsonNode fields, JsonNode schema) {
        List<Violation> out = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        schema.path("properties").fields().forEachRemaining(e -> {
            if (!"date".equals(e.getValue().path("format").asText())) {
                return;
            }
            JsonNode value = fields.path(e.getKey());
            if (!value.isTextual()) {
                return;
            }
            try {
                LocalDate date = LocalDate.parse(value.asText());
                if (date.isAfter(today) && !FORWARD_LOOKING.matcher(e.getKey()).matches()) {
                    out.add(new Violation(e.getKey(), name(), "date " + date + " is in the future"));
                }
            } catch (DateTimeParseException ex) {
                out.add(new Violation(e.getKey(), name(), "'" + value.asText() + "' is not a real ISO date"));
            }
        });
        return out;
    }
}
