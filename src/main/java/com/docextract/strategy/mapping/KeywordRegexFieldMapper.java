package com.docextract.strategy.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Naive baseline: find "Label: value" on a line, then coerce the value by the schema type.
 * Only top-level scalar properties are filled; arrays/objects are left null.
 */
@Component
public class KeywordRegexFieldMapper implements FieldMapper {

    static final double LABELLED = 0.8;
    static final double FALLBACK = 0.3;

    @Override
    public MappedFields map(String text, JsonNode schema) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        schema.path("properties").fields().forEachRemaining(e -> {
            Optional<Found> found = findField(text, e.getKey(), e.getValue());
            fields.put(e.getKey(), found.map(Found::value).orElse(null));
            confidence.put(e.getKey(), found.map(Found::confidence).orElse(0.0));
        });
        return new MappedFields(fields, confidence);
    }

    private record Found(Object value, double confidence) {
    }

    private Optional<Found> findField(String text, String name, JsonNode prop) {
        Kind kind = Kind.of(prop);
        if (kind == Kind.UNSUPPORTED) {
            return Optional.empty();
        }
        for (String label : labels(name, prop)) {
            Matcher m = labelPattern(label).matcher(text);
            while (m.find()) {
                Optional<Object> v = coerce(kind, m.group(1).trim());
                if (v.isPresent()) {
                    return Optional.of(new Found(v.get(), LABELLED));
                }
            }
        }
        return fallback(text, name, kind).map(v -> new Found(v, FALLBACK));
    }

    // Label must not be preceded by a letter, so "Total" does not match inside "Subtotal".
    private static Pattern labelPattern(String label) {
        String words = String.join("[\\s_-]*",
                Arrays.stream(label.trim().split("\\s+")).map(Pattern::quote).toList());
        return Pattern.compile("(?<![A-Za-z])" + words + "(?![A-Za-z])\\s*[:#-]?\\s*([^\\n\\r]+)",
                Pattern.CASE_INSENSITIVE);
    }

    static Set<String> labels(String name, JsonNode prop) {
        Set<String> labels = new LinkedHashSet<>();
        prop.path("x-labels").forEach(l -> labels.add(l.asText()));
        if (prop.hasNonNull("title")) {
            labels.add(prop.get("title").asText());
        }
        String fromName = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase();
        labels.add(fromName);
        if (fromName.endsWith(" number")) {
            String stem = fromName.substring(0, fromName.length() - " number".length());
            labels.add(stem + " no.");
            labels.add(stem + " no");
            labels.add(stem + " #");
        }
        return labels;
    }

    private static Optional<Object> coerce(Kind kind, String raw) {
        return switch (kind) {
            case NUMBER -> ValueParsers.firstAmount(raw).map(b -> (Object) b);
            case INTEGER -> ValueParsers.firstAmount(raw).map(BigDecimal::intValue).map(i -> (Object) i);
            case DATE -> ValueParsers.firstDate(raw).map(d -> (Object) d.toString());
            case STRING -> raw.isEmpty() ? Optional.empty() : Optional.of(raw);
            case UNSUPPORTED -> Optional.empty();
        };
    }

    // Unlabelled guesses: first date in the document; largest amount for total-like fields.
    private static Optional<Object> fallback(String text, String name, Kind kind) {
        if (kind == Kind.DATE) {
            return ValueParsers.firstDate(text).map(d -> (Object) d.toString());
        }
        if (kind == Kind.NUMBER && name.toLowerCase().matches(".*(total|amount|due).*")) {
            return ValueParsers.allAmounts(text).stream().max(Comparator.naturalOrder()).map(b -> (Object) b);
        }
        return Optional.empty();
    }

    enum Kind {
        STRING, NUMBER, INTEGER, DATE, UNSUPPORTED;

        static Kind of(JsonNode prop) {
            String type = prop.path("type").asText("string");
            if ("string".equals(type) && "date".equals(prop.path("format").asText())) {
                return DATE;
            }
            return switch (type) {
                case "string" -> STRING;
                case "number" -> NUMBER;
                case "integer" -> INTEGER;
                default -> UNSUPPORTED;
            };
        }
    }
}
