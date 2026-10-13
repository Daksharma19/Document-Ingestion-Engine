package com.docextract.strategy.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Rule-based mapper: works for any JSON Schema, with extra domain knowledge for invoices.
 * <ol>
 *   <li>Layout is normalised first (header-row/value-row forms and block labels become "Label: value").</li>
 *   <li>Each property is searched by label: its {@code x-labels}, {@code title}, the invoice vocabulary when its
 *       name is a known invoice concept, and words from its own name ({@code shippingMethod} → "shipping method").</li>
 *   <li>The value is coerced by the schema type and format; numeric dates follow the order the document uses.</li>
 *   <li>Without a label, schema-driven fallbacks: {@code pattern} (first match in the text), {@code enum}
 *       (first listed value that appears), {@code format} email/uri/date, then invoice heuristics.</li>
 * </ol>
 * Arrays of items are read by {@link LineItemExtractor}.
 */
@Component
public class KeywordRegexFieldMapper implements FieldMapper {

    static final double LABELLED = 0.8;
    static final double FALLBACK = 0.3;
    /** Table rows are found structurally, not by an explicit label: between the two. */
    static final double TABLE = 0.5;
    /** Found because it matches the schema's own pattern/enum/format: strong evidence, but unlabelled. */
    static final double SCHEMA_MATCH = 0.6;

    /** The "value" is really the rest of a longer label: one or two words then a colon. */
    private static final Pattern LONGER_LABEL = Pattern.compile("\\s*[A-Za-z][A-Za-z]*(?:\\s+[A-Za-z]+)?\\s*:");
    /** The number directly follows the label (optionally a currency): "TOTAL $313.90", not "TOTAL DISCOUNT $0.40". */
    private static final Pattern DIRECT_NUMBER = Pattern.compile(
            "^\\s*(?:[$€£₹¥]|USD|EUR|GBP|INR|AUD|CAD|Rs\\.?)?\\s*[-(]?\\d", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern URI = Pattern.compile("https?://[^\\s]+|www\\.[^\\s]+");
    private static final Pattern DOC_TITLE = Pattern.compile(
            "(?i)(tax\\s+)?(invoice|receipt|bill|statement|quotation|quote|estimate|purchase\\s+order|credit\\s+note|"
                    + "debit\\s+note|delivery\\s+note|packing\\s+slip|proforma(\\s+invoice)?|cash\\s+memo)");

    /** Per-document facts every field lookup needs. */
    private record Doc(String text, boolean monthFirst) {
    }

    @Override
    public MappedFields map(String rawText, JsonNode schema) {
        // Non-breaking and other Unicode spaces are not matched by \s; PDFs use them freely.
        String text = rawText.replaceAll("[\\u00A0\\u2007\\u202F\\u2000-\\u200A]", " ");
        Doc doc = new Doc(LayoutNormalizer.normalise(text), ValueParsers.isMonthFirst(text));
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Double> confidence = new LinkedHashMap<>();
        schema.path("properties").fields().forEachRemaining(e -> {
            Optional<Found> found = "array".equals(e.getValue().path("type").asText()) && isItemTable(e.getKey(), e.getValue())
                    ? LineItemExtractor.extract(text, e.getValue().path("items")).map(items -> new Found(items, TABLE))
                    : findField(doc, e.getKey(), e.getValue());
            fields.put(e.getKey(), found.map(Found::value).orElse(null));
            confidence.put(e.getKey(), found.map(Found::confidence).orElse(0.0));
        });
        return new MappedFields(fields, confidence);
    }

    private record Found(Object value, double confidence) {
    }

    private Optional<Found> findField(Doc doc, String name, JsonNode prop) {
        Kind kind = Kind.of(prop);
        if (kind == Kind.UNSUPPORTED) {
            return Optional.empty();
        }
        for (String label : labels(name, prop)) {
            Optional<Object> v = byLabel(doc, label, kind, prop);
            if (v.isPresent()) {
                Object value = v.get();
                if (kind == Kind.STRING && isIdentifier(name)) {
                    value = joinDigitGroups((String) value);
                }
                return Optional.of(new Found(value, LABELLED));
            }
        }
        Optional<Object> bySchema = schemaFallback(doc, prop, kind);
        if (bySchema.isPresent()) {
            return bySchema.map(v -> new Found(v, SCHEMA_MATCH));
        }
        return fallback(doc, name, kind).map(v -> new Found(v, FALLBACK));
    }

    /**
     * Every occurrence of the label, in document order; for numbers, occurrences where the number directly follows
     * the label are tried before ones with other words in between.
     */
    private static Optional<Object> byLabel(Doc doc, String label, Kind kind, JsonNode prop) {
        Matcher m = labelPattern(label).matcher(doc.text());
        List<String> direct = new ArrayList<>();
        List<String> indirect = new ArrayList<>();
        while (m.find()) {
            String raw = m.group(1);
            if (LONGER_LABEL.matcher(raw).lookingAt()) {
                continue; // "Total Items: 3" is a different label that merely starts with "Total".
            }
            if (kind == Kind.STRING && isNeighbouringPair(raw)) {
                continue; // "From:   Invoice Number   INV-3337": the next cells are another label's pair
            }
            boolean numeric = kind == Kind.NUMBER || kind == Kind.INTEGER;
            (numeric && !DIRECT_NUMBER.matcher(raw).lookingAt() ? indirect : direct).add(raw.trim());
        }
        direct.addAll(indirect);
        for (String raw : direct) {
            Optional<Object> v = coerce(kind, raw, doc, prop);
            if (v.isPresent()) {
                return v;
            }
        }
        return Optional.empty();
    }

    /** The text after a label is "Caption   value-with-digits": a neighbouring column's own label and value. */
    static boolean isNeighbouringPair(String raw) {
        List<String> cells = LayoutNormalizer.cells(raw);
        // "Asha Verma   Age: 34" is a value followed by another labelled pair, so it is not this case.
        return cells.size() >= 2 && cells.get(0).matches("[A-Za-z][A-Za-z #./&'-]*")
                && cells.get(1).matches(".*\\d.*") && !cells.get(1).contains(":");
    }

    // Label must not be preceded by a letter, so "Total" does not match inside "Subtotal".
    private static Pattern labelPattern(String label) {
        // Horizontal whitespace only: a label at the end of one line must not take its value from the next line.
        String words = String.join("[ \\t_-]*",
                Arrays.stream(label.trim().split("\\s+")).map(Pattern::quote).toList());
        return Pattern.compile("(?<![A-Za-z]|[A-Za-z]-)" + words + "(?![A-Za-z])[ \\t]*[:#-]?[ \\t]*([^\\n\\r]+)",
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
            labels.add(stem + " id");
        }
        return labels;
    }

    private static Optional<Object> coerce(Kind kind, String raw, Doc doc, JsonNode prop) {
        Optional<Object> v = switch (kind) {
            case NUMBER -> ValueParsers.firstAmount(raw).map(b -> (Object) b);
            case INTEGER -> ValueParsers.firstAmount(raw).map(BigDecimal::intValue).map(i -> (Object) i);
            case DATE -> ValueParsers.firstDate(raw, doc.monthFirst()).map(d -> (Object) d.toString());
            case STRING -> cleanString(raw);
            case UNSUPPORTED -> Optional.empty();
        };
        // A labelled string must still satisfy the schema's own constraints to be accepted.
        if (v.isPresent() && kind == Kind.STRING) {
            String s = (String) v.get();
            Optional<Pattern> pattern = schemaPattern(prop);
            if (pattern.isPresent()) {
                Matcher pm = pattern.get().matcher(s);
                return pm.find() ? Optional.of(pm.group()) : Optional.empty();
            }
            if (prop.path("enum").isArray()) {
                for (JsonNode option : prop.get("enum")) {
                    if (option.isTextual() && Pattern.compile("(?i)(?<![A-Za-z0-9])" + Pattern.quote(option.asText()) + "(?![A-Za-z0-9])")
                            .matcher(raw).find()) {
                        return Optional.of(option.asText());
                    }
                }
                return Optional.empty();
            }
            String format = prop.path("format").asText();
            if (format.equals("email")) {
                Matcher em = EMAIL.matcher(s);
                return em.find() ? Optional.of(em.group()) : Optional.empty();
            }
        }
        return v;
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

    /** The schema's {@code pattern}, unanchored so it can be searched for inside the text. */
    private static Optional<Pattern> schemaPattern(JsonNode prop) {
        if (!prop.hasNonNull("pattern")) {
            return Optional.empty();
        }
        String p = prop.get("pattern").asText().replaceAll("^\\^", "").replaceAll("(?<!\\\\)\\$$", "");
        try {
            return Optional.of(Pattern.compile("(?<![A-Za-z0-9])(?:" + p + ")(?![A-Za-z0-9])"));
        } catch (PatternSyntaxException e) {
            return Optional.empty();
        }
    }

    /** Unlabelled, but the schema itself says what the value looks like. */
    private static Optional<Object> schemaFallback(Doc doc, JsonNode prop, Kind kind) {
        String text = doc.text();
        Optional<Pattern> pattern = schemaPattern(prop);
        if (pattern.isPresent()) {
            Matcher m = pattern.get().matcher(text);
            return m.find() ? Optional.of(m.group()) : Optional.empty();
        }
        if (prop.path("enum").isArray()) {
            for (JsonNode option : prop.get("enum")) {
                if (option.isTextual() && Pattern.compile("(?i)(?<![A-Za-z0-9])" + Pattern.quote(option.asText()) + "(?![A-Za-z0-9])")
                        .matcher(text).find()) {
                    return Optional.of(option.asText());
                }
            }
            return Optional.empty();
        }
        if (kind == Kind.STRING) {
            String format = prop.path("format").asText();
            Matcher m = format.equals("email") ? EMAIL.matcher(text) : format.equals("uri") ? URI.matcher(text) : null;
            if (m != null && m.find()) {
                return Optional.of(m.group());
            }
        }
        return Optional.empty();
    }

    // Unlabelled guesses, only for known invoice concepts.
    private static Optional<Object> fallback(Doc doc, String name, Kind kind) {
        String text = doc.text();
        Optional<InvoiceVocabulary.Concept> concept = InvoiceVocabulary.conceptOf(name);
        Optional<Object> firstDate = kind == Kind.DATE
                ? ValueParsers.firstDate(text, doc.monthFirst()).map(d -> (Object) d.toString()) : Optional.empty();
        if (concept.isEmpty()) {
            return Optional.empty(); // an unknown field is never guessed: absent beats wrong
        }
        return switch (concept.get()) {
            // The issue date is usually the first date printed; a due date cannot be guessed that way.
            case INVOICE_DATE -> firstDate;
            case TOTAL -> kind == Kind.NUMBER
                    ? ValueParsers.allAmounts(text).stream().max(Comparator.naturalOrder()).map(b -> (Object) b)
                    : Optional.empty();
            case TAX -> kind == Kind.NUMBER ? taxSummary(text).map(b -> (Object) b) : Optional.empty();
            case VENDOR -> kind == Kind.STRING ? firstNameLine(text) : Optional.empty();
            case CURRENCY -> kind == Kind.STRING ? InvoiceVocabulary.detectCurrency(text).map(c -> (Object) c) : Optional.empty();
            case INVOICE_NUMBER -> kind == Kind.STRING ? standaloneNumber(text) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /** "# 50056" or "No. 1234" alone on a line (typically under the document title). */
    private static final Pattern STANDALONE_NUMBER = Pattern.compile(
            "(?im)^[ \\t]*(?:#|No\\.?|Nr\\.?)[ \\t]*:?[ \\t]*([A-Za-z0-9][A-Za-z0-9/_-]*\\d[A-Za-z0-9/_-]*)[ \\t]*$");

    static Optional<Object> standaloneNumber(String text) {
        Matcher m = STANDALONE_NUMBER.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
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

    /**
     * The issuer's name: the first cell, top of the document, that is a name (letters and name punctuation, no
     * digits, no "Label:") and not the document's title ("INVOICE", "Tax Invoice"). Scanning stops at the
     * recipient block ("To:", "Bill To"), so the customer is never taken for the vendor.
     */
    static Optional<Object> firstNameLine(String text) {
        for (String line : text.split("\\R")) {
            if (line.trim().matches("(?i)^(bill(ed)?\\s+to|ship\\s+to|sold\\s+to|to|customer|client)\\b.*")) {
                return Optional.empty();
            }
            for (String cell : LayoutNormalizer.cells(line)) {
                String t = cell.trim();
                if (t.length() >= 3 && t.matches("[\\p{L}][\\p{L}&'.,\\s-]*") && t.chars().filter(Character::isLetter).count() >= 3
                        && !DOC_TITLE.matcher(t).matches()) {
                    return cleanString(t);
                }
                break; // only the left-most cell of a line can start the letterhead
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
