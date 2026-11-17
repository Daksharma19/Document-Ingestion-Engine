package com.docextract.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The built-in schemas ({@code classpath:schemas/*.json}): ready-made JSON Schemas (plus default rules and
 * classification keywords) for common document types. Clients can send a schema's {@code id} instead of a schema,
 * or {@code auto} to let {@link DocumentClassifier} pick one.
 */
@Component
public class SchemaCatalog {

    /** @param keywords phrases that indicate this document type (used by the classifier) */
    public record SchemaPreset(String id, String label, String category, String description,
                               List<String> keywords, List<String> rules, JsonNode schema) {
    }

    private final List<SchemaPreset> presets;

    public SchemaCatalog(ObjectMapper json) {
        List<SchemaPreset> loaded = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:schemas/*.json")) {
                try (InputStream in = r.getInputStream()) {
                    JsonNode n = json.readTree(in);
                    loaded.add(new SchemaPreset(n.path("id").asText(), n.path("label").asText(),
                            n.path("category").asText(""), n.path("description").asText(""),
                            strings(n.path("keywords")), strings(n.path("rules")), n.path("schema")));
                    order.add(n.path("order").asInt(1000));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not load built-in schemas", e);
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < loaded.size(); i++) {
            idx.add(i);
        }
        idx.sort(Comparator.comparing(order::get));
        this.presets = idx.stream().map(loaded::get).toList();
    }

    public List<SchemaPreset> all() {
        return presets;
    }

    public Optional<SchemaPreset> find(String id) {
        return presets.stream().filter(p -> p.id().equalsIgnoreCase(id.trim())).findFirst();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }
}
