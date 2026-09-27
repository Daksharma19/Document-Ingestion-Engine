package com.docextract.service;

import com.docextract.api.ExtractionResponse;
import com.docextract.document.Document;
import com.docextract.strategy.ExtractionResult;
import com.docextract.strategy.ExtractionStrategy;
import com.docextract.validation.ValidationEngine;
import com.docextract.validation.ValidationReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

@Service
public class ExtractionService {

    private final ObjectMapper mapper;
    private final List<ExtractionStrategy> strategies;
    private final ValidationEngine validationEngine;

    public ExtractionService(ObjectMapper mapper, List<ExtractionStrategy> strategies,
                             ValidationEngine validationEngine) {
        this.mapper = mapper;
        this.validationEngine = validationEngine;
        this.strategies = strategies.stream()
                .sorted(Comparator.comparing(ExtractionStrategy::costTier))
                .toList();
    }

    public ExtractionResponse extract(MultipartFile file, String schemaJson) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("file is required and must not be empty");
        }
        JsonNode schema = parseSchema(schemaJson);
        Document document = toDocument(file);

        // Routing comes later; for now always use the cheapest tier.
        ExtractionStrategy strategy = strategies.getFirst();
        ExtractionResult result = strategy.extract(document, schema);
        ValidationReport report = validationEngine.validate(mapper.valueToTree(result.fields()), schema);
        return new ExtractionResponse(result.fields(), result.confidence(),
                strategy.costTier().name(), result.costUnits(), report.violations());
    }

    private Document toDocument(MultipartFile file) {
        try {
            return new Document(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new InvalidRequestException("could not read uploaded file");
        }
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
