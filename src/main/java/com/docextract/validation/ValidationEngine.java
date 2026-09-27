package com.docextract.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.SpecVersionDetector;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Structural validation against the caller's JSON Schema, then every registered business rule. */
@Service
public class ValidationEngine {

    public static final String SCHEMA_RULE = "json-schema";

    private final List<BusinessRule> rules;

    public ValidationEngine(List<BusinessRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public ValidationReport validate(JsonNode fields, JsonNode schema) {
        List<Violation> violations = new ArrayList<>();
        for (ValidationMessage m : compile(schema).validate(fields)) {
            String path = m.getInstanceLocation().toString().replaceFirst("^\\$\\.?", "");
            violations.add(new Violation(path.isEmpty() ? m.getProperty() : path, SCHEMA_RULE, m.getMessage()));
        }
        for (BusinessRule rule : rules) {
            violations.addAll(rule.check(fields, schema));
        }
        violations.sort(Comparator.comparing(Violation::field, Comparator.nullsFirst(Comparator.naturalOrder())));
        return new ValidationReport(violations);
    }

    // Honour the schema's own $schema dialect; default to 2020-12 when absent.
    private static JsonSchema compile(JsonNode schema) {
        SpecVersion.VersionFlag version = schema.has("$schema")
                ? SpecVersionDetector.detect(schema)
                : SpecVersion.VersionFlag.V202012;
        return JsonSchemaFactory.getInstance(version).getSchema(schema);
    }
}
