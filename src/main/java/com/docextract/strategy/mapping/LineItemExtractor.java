package com.docextract.strategy.mapping;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads an item table from plain text, with no knowledge of the template:
 * <ol>
 *   <li>the table starts after a header row (two or more of description/qty/rate/price/amount/...), else at the top;</li>
 *   <li>it ends at the first summary line (Subtotal/Total/Tax/Amount payable/... followed by an amount);</li>
 *   <li>a row is a line ending in numeric columns, the last one money-like. The trailing numbers are
 *       [qty] [unit price] amount; the text before them is the description, or, when that is only tax noise
 *       (e.g. "CGST(2.5%) 0.53"), the nearest preceding descriptive line (multi-line POS layouts).</li>
 * </ol>
 * Columns are mapped onto the item schema's properties by name (description/name/item, qty/quantity,
 * unitPrice/rate/price, amount/total/lineTotal).
 */
public final class LineItemExtractor {

    private LineItemExtractor() {
    }

    private static final Pattern HEADER_WORD = Pattern.compile(
            "(?i)^(description|item|items|particulars|product|products|service|services|details|qty|quantity|units?|"
                    + "rate|price|unitprice|amount|amt|total|hsn|sac|mrp|disc|discount)$");
    private static final Pattern SUMMARY_START = Pattern.compile(
            "(?i)^\\s*(sub\\s*-?\\s*total|total|grand\\s+total|net\\s+(amount|total|payable)|amount\\s+(payable|due)|"
                    + "balance(\\s+due)?|tax(able)?|vat|gst|round(ing)?\\s*off|discount|less|cash|change|tender|payment|paid)\\b");
    private static final Pattern NUMBER = Pattern.compile("^[-(]?(?:[$€£₹]|Rs\\.?)?\\d[\\d,]*(?:\\.\\d+)?\\)?$");
    private static final Pattern MONEY = Pattern.compile("\\.\\d{2}\\)?$");
    private static final Pattern TAX_NOISE = Pattern.compile("(?i)(c|s|i|ut)?gst|vat|tax|%|cess");
    private static final Pattern CODE_LIKE = Pattern.compile("^[A-Z0-9/_-]*\\d[A-Z0-9/_-]*$");

    enum Role { DESCRIPTION, QUANTITY, UNIT_PRICE, AMOUNT }

    public static Optional<List<Map<String, Object>>> extract(String text, JsonNode itemSchema) {
        String[] lines = text.split("\\R");
        int header = findHeader(lines);
        int start = header + 1;
        List<Row> rows = new ArrayList<>();
        int lastUsed = header;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> tokens = new ArrayList<>(Arrays.asList(line.split("\\s+")));
            List<BigDecimal> numbers = new ArrayList<>();
            while (!tokens.isEmpty() && NUMBER.matcher(tokens.getLast()).matches()) {
                numbers.addFirst(parse(tokens.removeLast()));
            }
            if (SUMMARY_START.matcher(line).lookingAt() && !numbers.isEmpty()) {
                break;
            }
            if (numbers.isEmpty() || !MONEY.matcher(line).find() && !(header >= 0 && numbers.size() >= 2)) {
                continue;
            }
            String desc = String.join(" ", tokens).trim();
            if (desc.contains(":")) {
                continue; // "HSN/SAC: 996331", "Phone: 123" are labels, not rows
            }
            if (letters(desc) < 2 || TAX_NOISE.matcher(desc).find() && letters(desc.replaceAll("(?i)(c|s|i|ut)?gst|vat|tax|cess", "")) < 3) {
                int found = descriptionAbove(lines, i, lastUsed);
                if (found < 0) {
                    continue;
                }
                desc = lines[found].trim();
            }
            rows.add(new Row(desc, numbers));
            lastUsed = i;
        }
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Role> columns = columns(itemSchema);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(toItem(r, columns, itemSchema));
        }
        return Optional.of(out);
    }

    private record Row(String description, List<BigDecimal> numbers) {
    }

    private static int findHeader(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            long words = Arrays.stream(lines[i].trim().split("[^A-Za-z]+")).filter(w -> HEADER_WORD.matcher(w).matches()).count();
            if (words >= 2 && !MONEY.matcher(lines[i].trim()).find()) {
                return i;
            }
        }
        return -1;
    }

    /** Nearest line above (after the previous row) that reads like a product name, not a code or label. */
    private static int descriptionAbove(String[] lines, int row, int floor) {
        for (int j = row - 1; j > floor && j >= row - 3; j--) {
            String t = lines[j].trim();
            if (letters(t) >= 3 && !t.contains(":") && !CODE_LIKE.matcher(t).matches() && !MONEY.matcher(t).find()) {
                return j;
            }
        }
        return -1;
    }

    private static Map<String, Object> toItem(Row r, Map<String, Role> columns, JsonNode itemSchema) {
        List<BigDecimal> n = r.numbers();
        BigDecimal amount = n.getLast();
        BigDecimal qty = null;
        BigDecimal price = null;
        if (n.size() >= 3) {
            qty = n.get(n.size() - 3);
            price = n.get(n.size() - 2);
        } else if (n.size() == 2) {
            BigDecimal first = n.getFirst();
            if (first.stripTrailingZeros().scale() <= 0 && first.compareTo(BigDecimal.valueOf(1000)) < 0) {
                qty = first;
            } else {
                price = first;
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        for (Map.Entry<String, Role> c : columns.entrySet()) {
            Object v = switch (c.getValue()) {
                case DESCRIPTION -> r.description();
                case QUANTITY -> qty;
                case UNIT_PRICE -> price;
                case AMOUNT -> amount;
            };
            if (v instanceof BigDecimal b && "integer".equals(itemSchema.path("properties").path(c.getKey()).path("type").asText())) {
                v = b.intValue();
            }
            if (v != null) {
                item.put(c.getKey(), v);
            }
        }
        return item;
    }

    /** Which schema property receives which column; defaults when the item schema lists no properties. */
    static Map<String, Role> columns(JsonNode itemSchema) {
        Map<String, Role> out = new LinkedHashMap<>();
        JsonNode props = itemSchema.path("properties");
        if (!props.isObject() || props.isEmpty()) {
            out.put("description", Role.DESCRIPTION);
            out.put("quantity", Role.QUANTITY);
            out.put("unitPrice", Role.UNIT_PRICE);
            out.put("amount", Role.AMOUNT);
            return out;
        }
        props.fieldNames().forEachRemaining(name -> {
            String n = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            Role role = n.matches(".*(desc|name|item|product|particular|title|service|detail).*") ? Role.DESCRIPTION
                    : n.matches("(qty|quantity|units?|count|nos)") ? Role.QUANTITY
                    : n.matches(".*(unitprice|unitcost|price|rate|mrp).*") ? Role.UNIT_PRICE
                    : n.matches(".*(amount|total|value|net|linetotal|cost).*") ? Role.AMOUNT
                    : null;
            if (role != null) {
                out.put(name, role);
            }
        });
        return out;
    }

    private static BigDecimal parse(String token) {
        boolean negative = token.startsWith("-") || token.startsWith("(");
        BigDecimal v = new BigDecimal(token.replaceAll("[^0-9.]", ""));
        return negative ? v.negate() : v;
    }

    private static long letters(String s) {
        return s.chars().filter(Character::isLetter).count();
    }
}
