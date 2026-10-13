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
 * Top-level scalars are filled by label (plus a built-in invoice vocabulary); arrays of items via {@link LineItemExtractor}.
 */
@Component
public class KeywordRegexFieldMapper implements FieldMapper {

    static final double LABELLED = 0.8;
    static final double FALLBACK = 0.3;
    /** Table rows are found structurally, not by an explicit label: between the two. */
    static final double TABLE = 0.5;

    /** The "value" is really the rest of a longer label: one or two words then a colon. */
    private static final Pattern LONGER_LABEL = Pattern.compile("\\s*[A-Za-z][A-Za-z]*(?:\\s+[A-Za-z]+)?\\s*:");

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
        if ("array".equals(prop.path("type").asText()) && isItemTable(name, prop)) {
            return LineItemExtractor.extract(text, prop.path("items")).map(items -> new Found(items, TABLE));
        }
        if (kind == Kind.UNSUPPORTED) {
            return Optional.empty();
        }
        for (String label : labels(name, prop)) {
            Matcher m = labelPattern(label).matcher(text);
            while (m.find()) {
                if (LONGER_LABEL.matcher(m.group(1)).lookingAt()) {
                    // "Total Items: 3" is a different label that merely starts with "Total".
                    continue;
                }
                Optional<Object> v = coerce(kind, m.group(1).trim());
                if (v.isPresent() && kind == Kind.STRING && isIdentifier(name)) {
                    v = v.map(s -> joinDigitGroups((String) s));
                }
                if (v.isPresent()) {
                    return Optional.of(new Found(v.get(), LABELLED));
                }
            }
        }
        return fallback(text, name, kind).map(v -> new Found(v, FALLBACK));
    }

    // Label must not be preceded by a letter, so "Total" does not match inside "Subtotal".
    private static Pattern labelPattern(String label) {
        // Horizontal whitespace only: a label at the end of one line must not take its value from the next line.
        String words = String.join("[ \\t_-]*",
                Arrays.stream(label.trim().split("\\s+")).map(Pattern::quote).toList());
        return Pattern.compile("(?<![A-Za-z])" + words + "(?![A-Za-z])[ \\t]*[:#-]?[ \\t]*([^\\n\\r]+)",
                Pattern.CASE_INSENSITIVE);
    }

    static Set<String> labels(String name, JsonNode prop) {
        Set<String> labels = new LinkedHashSet<>();
        prop.path("x-labels").forEach(l -> labels.add(l.asText()));
        if (prop.hasNonNull("title")) {
            labels.add(prop.get("title").asText());
        }
        InvoiceVocabulary.conceptOf(name).ifPresent(c -> labels.addAll(c.labels()));
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
            case STRING -> cleanString(raw);
            case UNSUPPORTED -> Optional.empty();
        };
    }

    /**
     * A label's value ends at a column gap (2+ spaces, or a table rule "|"), which drops text from a
     * neighbouring column and OCR speckle noise; trailing punctuation is trimmed.
     */
    static Optional<Object> cleanString(String raw) {
        String v = raw.split("\\s{2,}|\\s\\|")[0].trim();
        v = v.replaceAll("[\\s.,;:|_~-]+$", "");
        return v.isEmpty() ? Optional.empty() : Optional.of(v);
    }

    /** An array of objects, or an array whose name says it lists items/lines/products. */
    static boolean isItemTable(String name, JsonNode prop) {
        return "object".equals(prop.path("items").path("type").asText())
                || name.toLowerCase().matches(".*(item|line|product|entries|charges|services).*");
    }

    static boolean isIdentifier(String name) {
        return name.matches("(?i).*(number|no|num|id|code|ref|reference)$");
    }

    /** OCR often splits a digit run ("NW-550 12"); identifiers never contain digit-space-digit. */
    static String joinDigitGroups(String s) {
        return s.replaceAll("(?<=\\d) (?=\\d)", "");
    }

    // Unlabelled guesses: first date in the document; largest amount for total-like fields (not subtotals);
    // for party-name fields, the first line that is a name rather than an address or amount.
    private static Optional<Object> fallback(String text, String name, Kind kind) {
        Optional<InvoiceVocabulary.Concept> concept = InvoiceVocabulary.conceptOf(name);
        if (concept.isEmpty()) {
            // Unknown field: only an unlabelled date can be guessed with any sense.
            return kind == Kind.DATE ? ValueParsers.firstDate(text).map(d -> (Object) d.toString()) : Optional.empty();
        }
        return switch (concept.get()) {
            // The issue date is usually the first date printed; a due date cannot be guessed that way.
            case INVOICE_DATE -> kind == Kind.DATE ? ValueParsers.firstDate(text).map(d -> (Object) d.toString()) : Optional.empty();
            case TOTAL -> kind == Kind.NUMBER
                    ? ValueParsers.allAmounts(text).stream().max(Comparator.naturalOrder()).map(b -> (Object) b)
                    : Optional.empty();
            case TAX -> kind == Kind.NUMBER ? taxSummary(text).map(b -> (Object) b) : Optional.empty();
            case VENDOR -> kind == Kind.STRING ? firstNameLine(text) : Optional.empty();
            case CURRENCY -> kind == Kind.STRING ? InvoiceVocabulary.detectCurrency(text).map(c -> (Object) c) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** A tax-summary row: a tax name followed only by numbers/rates, e.g. "CGST 54.33 2.50% 1.36" or "VAT 20% 12.00". */
    private static final Pattern TAX_ROW = Pattern.compile(
            "(?im)^[ \\t]*(?:CGST|SGST|UTGST|IGST|GST|VAT|Sales[ \\t]+Tax|Cess)\\b[ \\t]*(?:\\(?[\\d.,]+%\\)?[ \\t]*|[\\d.,]+[ \\t]*)*?([\\d,]+\\.\\d{2})[ \\t]*$");

    /** Sum of the last amount on every tax-summary row (CGST + SGST, VAT bands, ...). */
    static Optional<BigDecimal> taxSummary(String text) {
        Matcher m = TAX_ROW.matcher(text);
        BigDecimal sum = null;
        while (m.find()) {
            BigDecimal v = new BigDecimal(m.group(1).replace(",", ""));
            sum = sum == null ? v : sum.add(v);
        }
        return Optional.ofNullable(sum);
    }

    /** First non-blank line made only of letters and name punctuation (no digits, no "Label:"). */
    static Optional<Object> firstNameLine(String text) {
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (t.length() >= 3 && t.matches("[\\p{L}][\\p{L}&'.,\\s-]*") && t.chars().filter(Character::isLetter).count() >= 3) {
                return cleanString(t);
            }
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
