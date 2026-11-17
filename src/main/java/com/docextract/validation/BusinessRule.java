package com.docextract.validation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A semantic check the JSON Schema cannot express. Implementations are Spring beans and are
 * picked up automatically. A rule must return no violations when its fields are absent.
 */
public interface BusinessRule {

    String name();

    List<Violation> check(JsonNode fields, JsonNode schema);
}
