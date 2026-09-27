package com.docextract.service;

import com.docextract.api.ExtractionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ExtractionService {

    private final ObjectMapper mapper;

    public ExtractionService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ExtractionResponse extract(MultipartFile file, String schemaJson) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("file is required and must not be empty");
        }
        JsonNode schema = parseSchema(schemaJson);

        // Stub: echo every schema property with a null value and zero confidence.
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        schema.path("properties").fieldNames().forEachRemaining(name -> {
            fields.put(name, null);
            confidence.put(name, 0.0);
        });
        return new ExtractionResponse(fields, confidence, "STUB", 0);
    }

    private JsonNode parseSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new InvalidRequestException("schema is required");
        }
        try {
            JsonNode node = mapper.readTree(schemaJson);
            if (!node.isObject()) {
                throw new InvalidRequestException("schema must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("schema is not valid JSON: " + e.getOriginalMessage());
        }
    }
}
