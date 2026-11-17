package com.docextract.strategy.mapping;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/** Turns raw document text into values for the schema's top-level properties. */
public interface FieldMapper {

    MappedFields map(String text, JsonNode schema);

    record MappedFields(Map<String, Object> fields, Map<String, Double> confidence) {
    }
}
