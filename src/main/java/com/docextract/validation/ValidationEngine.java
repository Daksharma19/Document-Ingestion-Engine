package com.docextract.validation;

import com.docextract.validation.expr.RuleExpression;
import com.docextract.validation.expr.RuleExpression.MissingValueException;
import com.docextract.validation.expr.RuleExpression.RuleSyntaxException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.SpecVersionDetector;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Structural validation against the caller's JSON Schema (required, types, formats, ranges), then every
 * registered {@link BusinessRule} bean, then declarative {@link RuleExpression} rules. Expression rules come
 * from the schema's {@code "x-rules": ["sum(lineItems[].amount) == subtotal", ...]} plus any passed per call.
 */
@Service
public class ValidationEngine {

    public static final String SCHEMA_RULE = "json-schema";
    public static final String EXPRESSION_RULE = "expression";

    // Formats ("date", "email", ...) are assertions, not just annotations, so a bad date fails validation.
    private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder()
            .pathType(PathType.LEGACY).formatAssertionsEnabled(true).build();

    private final List<BusinessRule> rules;

    public ValidationEngine(List<BusinessRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public ValidationReport validate(JsonNode fields, JsonNode schema) {
        return validate(fields, schema, List.of());
    }

    public ValidationReport validate(JsonNode fields, JsonNode schema, List<RuleExpression> extraRules) {
        List<Violation> violations = new ArrayList<>();
        for (ValidationMessage m : compile(schema).validate(fields)) {
            String path = m.getInstanceLocation().toString().replaceFirst("^\\$\\.?", "");
            violations.add(new Violation(path.isEmpty() ? m.getProperty() : path, SCHEMA_RULE, m.getMessage()));
        }
        for (BusinessRule rule : rules) {
            violations.addAll(rule.check(fields, schema));
        }
        List<RuleResult> ruleResults = new ArrayList<>();
        List<RuleExpression> expressions = new ArrayList<>(schemaRules(schema));
        expressions.addAll(extraRules);
        for (RuleExpression expr : expressions) {
            RuleResult result = run(expr, fields);
            ruleResults.add(result);
            if (result.outcome() == RuleResult.Outcome.FAILED) {
                List<String> related = expr.referencedFields();
                violations.add(new Violation(related.isEmpty() ? null : related.getLast(), EXPRESSION_RULE,
                        result.detail(), related));
            }
        }
        violations.sort(Comparator.comparing(Violation::field, Comparator.nullsFirst(Comparator.naturalOrder())));
        return new ValidationReport(violations, ruleResults);
    }

    /**
     * Validates a tier's output. A null field means "the tier found nothing", which is absence, not a value of
     * the wrong type: nulls are dropped first so "required" reports missing mandatory fields and optional
     * fields that were not found pass.
     */
    public ValidationReport validateExtraction(JsonNode fields, JsonNode schema, List<RuleExpression> extraRules) {
        JsonNode present = fields;
        if (fields.isObject()) {
            ObjectNode copy = ((ObjectNode) fields).deepCopy();
            copy.properties().removeIf(e -> e.getValue().isNull());
            present = copy;
        }
        return validate(present, schema, extraRules);
    }

    /** Parses the schema's "x-rules"; a malformed rule is the caller's error, reported as RuleSyntaxException. */
    public static List<RuleExpression> schemaRules(JsonNode schema) {
        List<RuleExpression> out = new ArrayList<>();
        JsonNode xr = schema.path("x-rules");
        if (xr.isMissingNode()) {
            return out;
        }
        if (!xr.isArray()) {
            throw new RuleSyntaxException("x-rules must be an array of rule strings");
        }
        for (JsonNode r : xr) {
            if (!r.isTextual()) {
                throw new RuleSyntaxException("x-rules entries must be strings, got " + r);
            }
            out.add(RuleExpression.parse(r.asText()));
        }
        return out;
    }

    private static RuleResult run(RuleExpression expr, JsonNode fields) {
        try {
            boolean ok = expr.test(fields);
            return new RuleResult(expr.source(), ok ? RuleResult.Outcome.PASSED : RuleResult.Outcome.FAILED,
                    ok ? "" : "rule '" + expr.source() + "' is false (" + expr.explain(fields) + ")",
                    expr.referencedFields());
        } catch (MissingValueException e) {
            return new RuleResult(expr.source(), RuleResult.Outcome.SKIPPED, e.getMessage(), expr.referencedFields());
        } catch (RuleSyntaxException e) {
            // Type errors at evaluation time (e.g. comparing a string with a number) count as a failed rule.
            return new RuleResult(expr.source(), RuleResult.Outcome.FAILED,
                    "rule '" + expr.source() + "' could not be applied: " + e.getMessage(), expr.referencedFields());
        }
    }

    // Honour the schema's own $schema dialect; default to 2020-12 when absent.
    private static JsonSchema compile(JsonNode schema) {
        SpecVersion.VersionFlag version = schema.has("$schema")
                ? SpecVersionDetector.detect(schema)
                : SpecVersion.VersionFlag.V202012;
        return JsonSchemaFactory.getInstance(version).getSchema(schema, CONFIG);
    }
}
