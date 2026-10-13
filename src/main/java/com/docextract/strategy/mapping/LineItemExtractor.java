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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an item table from plain text, with no knowledge of the template:
 * <ol>
 *   <li>The header row (the line naming the most columns: qty, item #, description, unit price, discount, tax,
 *       amount, ...) fixes the column order. Numeric columns left of the description are read from the start
 *       of a row, those to its right from the end, so "QTY | ITEM # | DESCRIPTION | PRICE | DISC | TOTAL" and
 *       "ITEM | QTY | RATE | AMOUNT" both work. Without a header: [qty] [price] amount at the end of the row.</li>
 *   <li>The table ends at the first summary line (Subtotal/Total/Tax/Amount payable/... with an amount).</li>
 *   <li>A row whose numbers wrap to the next line ("1.00 Web Design" / "details... $85.00 0.00% $85.00") is joined.
 *       A row with only tax noise before its numbers (POS slips) takes its name from the nearest line above.</li>
 * </ol>
 * Columns are mapped onto the item schema's properties by name.
 */
public final class LineItemExtractor {

    private LineItemExtractor() {
    }

    enum Role { DESCRIPTION, QUANTITY, UNIT_PRICE, AMOUNT, CODE, DISCOUNT, TAX }

    // Longer phrases first so "unit price" is one PRICE column, "item #" a CODE, "line total" one AMOUNT.
    private static final Pattern COLUMN = Pattern.compile(
            "(?i)(?<![A-Za-z])(?:"
                    + "(?<code>item\\s*#|item\\s*no\\.?|item\\s*code|sku|hsn(?:/sac)?|sac|part\\s*(?:no|#))"
                    + "|(?<qty>hrs/qty|qty|quantity|hours|hrs|nos)"
                    + "|(?<price>unit\\s*price|unit\\s*cost|rate/price|price/rate|rate|price|mrp)"
                    + "|(?<disc>discount|disc\\.?|adjust(?:ment)?)"
                    + "|(?<tax>tax|gst|vat)"
                    + "|(?<amount>line\\s*total|sub\\s*total|amount|amt|total|net)"
                    + "|(?<desc>description|item\\s*name|items?|services?|products?|particulars|details)"
                    + ")(?![A-Za-z])");
    private static final Pattern SUMMARY_START = Pattern.compile(
            "(?i)^\\s*(sub\\s*-?\\s*total|total|grand\\s+total|net\\s+(amount|total|payable)|amount\\s+(payable|due)|"
                    + "balance(\\s+due)?|tax(able)?|sales\\s+tax|vat|gst|round(ing)?\\s*off|discount|less|cash|change|"
                    + "tender|payment|paid|shipping|freight)\\b");
    private static final Pattern NUMBER = Pattern.compile("^[-(]?(?:[$€£₹]|Rs\\.?)?\\d[\\d,]*(?:\\.\\d+)?%?\\)?$");
    private static final Pattern MONEY = Pattern.compile("\\.\\d{2}\\)?$|\\.\\d{2}\\s");
    private static final Pattern TAX_NOISE = Pattern.compile("(?i)(c|s|i|ut)?gst|vat|tax|%|cess");
    private static final Pattern CODE_LIKE = Pattern.compile("^[A-Z0-9/_-]*\\d[A-Z0-9/_-]*$");

    /** Column roles named in a line, in order; used to recognise and read item-table headers. */
    static List<Role> headerRoles(String line) {
        List<Role> roles = new ArrayList<>();
        Matcher m = COLUMN.matcher(line);
        while (m.find()) {
            roles.add(m.group("code") != null ? Role.CODE
                    : m.group("qty") != null ? Role.QUANTITY
                    : m.group("price") != null ? Role.UNIT_PRICE
                    : m.group("disc") != null ? Role.DISCOUNT
                    : m.group("tax") != null ? Role.TAX
                    : m.group("amount") != null ? Role.AMOUNT
                    : Role.DESCRIPTION);
        }
        return roles;
    }

    /** A line that names at least two table columns including a quantity/price/amount, and carries no money. */
    static boolean isTableHeader(String line) {
        List<Role> roles = headerRoles(line);
        if (line.contains(":") || line.matches(".*\\d.*")) {
            return false;
        }
        return roles.size() >= 2 && roles.stream().anyMatch(r -> r == Role.QUANTITY || r == Role.UNIT_PRICE || r == Role.AMOUNT)
                && !MONEY.matcher(line.trim() + " ").find();
    }

    /**
     * Documents often have several header-like rows (the item table, a tax summary table...). Each candidate
     * is tried and the one yielding the most rows wins (ties: the more specific header). Only if no header
     * yields rows is the table read without one.
     */
    public static Optional<List<Map<String, Object>>> extract(String text, JsonNode itemSchema) {
        String[] lines = text.split("\\R");
        List<Map<String, Object>> best = List.of();
        int bestRoles = -1;
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (isTableHeader(lines[i])) {
                candidates.add(i);
            }
        }
        for (int header : candidates) {
            List<Map<String, Object>> rows = extract(lines, header, itemSchema);
            int roles = header >= 0 ? headerRoles(lines[header]).size() : 0;
            if (rows.size() > best.size() || rows.size() == best.size() && !rows.isEmpty() && roles > bestRoles) {
                best = rows;
                bestRoles = roles;
            }
        }
        if (best.isEmpty()) {
            // No header produced rows: fall back to the header-less reading (receipts without column captions).
            best = extract(lines, -1, itemSchema);
        }
        return best.isEmpty() ? Optional.empty() : Optional.of(best);
    }

    private static List<Map<String, Object>> extract(String[] lines, int header, JsonNode itemSchema) {
        List<Role> roles = header >= 0 ? headerRoles(lines[header]) : List.of();
        int descAt = roles.indexOf(Role.DESCRIPTION);
        List<Role> leading = descAt > 0 ? roles.subList(0, descAt) : List.of();
        List<Role> trailing = descAt >= 0 ? roles.subList(descAt + 1, roles.size()) : roles;

        List<Row> rows = new ArrayList<>();
        int lastUsed = header;
        Row pending = null; // leading numbers + description, numbers expected on the next line
        for (int i = header + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> tokens = new ArrayList<>(Arrays.asList(line.split("\\s+")));
            List<BigDecimal> tail = new ArrayList<>();
            int tailLimit = trailing.isEmpty() ? Integer.MAX_VALUE : trailing.size();
            while (!tokens.isEmpty() && tail.size() < tailLimit && NUMBER.matcher(tokens.getLast()).matches()) {
                tail.addFirst(parse(tokens.removeLast()));
            }
            if (SUMMARY_START.matcher(line).lookingAt() && !tail.isEmpty()) {
                break;
            }
            List<BigDecimal> head = new ArrayList<>();
            while (head.size() < leading.size() && !tokens.isEmpty() && NUMBER.matcher(tokens.getFirst()).matches()) {
                head.add(parse(tokens.removeFirst()));
            }
            String desc = String.join(" ", tokens).trim();

            boolean hasAmount = !tail.isEmpty() && (MONEY.matcher(line).find() || header >= 0 && tail.size() >= 2);
            if (!hasAmount) {
                // A wrapped row: leading numbers and a name now, amounts on the next line.
                if (header >= 0 && letters(desc) >= 2 && !desc.contains(":") && (!head.isEmpty() || tail.size() == 1 && descAt > 0)) {
                    if (head.isEmpty() && !tail.isEmpty()) {
                        head.addAll(tail);
                    }
                    pending = new Row(desc, head, List.of());
                }
                continue;
            }
            if (desc.contains(":")) {
                pending = null;
                continue; // "HSN/SAC: 996331", "Phone: 123" are labels, not rows
            }
            if (pending != null && head.isEmpty()) {
                rows.add(new Row(pending.description(), pending.head(), tail));
                pending = null;
                lastUsed = i;
                continue;
            }
            pending = null;
            if (letters(desc) < 2 || TAX_NOISE.matcher(desc).find() && letters(desc.replaceAll("(?i)(c|s|i|ut)?gst|vat|tax|cess", "")) < 3) {
                int found = descriptionAbove(lines, i, lastUsed);
                if (found < 0) {
                    continue;
                }
                desc = lines[found].trim();
            }
            rows.add(new Row(desc, head, tail));
            lastUsed = i;
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Role> columns = columns(itemSchema);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(toItem(r, leading, trailing, header >= 0, columns, itemSchema));
        }
        return out;
    }

    private record Row(String description, List<BigDecimal> head, List<BigDecimal> tail) {
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

    private static Map<String, Object> toItem(Row r, List<Role> leading, List<Role> trailing, boolean hasHeader,
                                              Map<String, Role> columns, JsonNode itemSchema) {
        Map<Role, BigDecimal> values = new LinkedHashMap<>();
        if (hasHeader && !trailing.isEmpty()) {
            for (int k = 0; k < r.head().size() && k < leading.size(); k++) {
                values.putIfAbsent(leading.get(k), r.head().get(k));
            }
            // Right-align the trailing numbers with the trailing columns.
            int offset = trailing.size() - r.tail().size();
            for (int k = 0; k < r.tail().size(); k++) {
                if (k + offset >= 0) {
                    values.putIfAbsent(trailing.get(k + offset), r.tail().get(k));
                }
            }
        } else {
            List<BigDecimal> n = r.tail();
            values.put(Role.AMOUNT, n.getLast());
            if (n.size() >= 3) {
                values.put(Role.QUANTITY, n.get(n.size() - 3));
                values.put(Role.UNIT_PRICE, n.get(n.size() - 2));
            } else if (n.size() == 2) {
                BigDecimal first = n.getFirst();
                boolean wholeSmall = first.stripTrailingZeros().scale() <= 0 && first.compareTo(BigDecimal.valueOf(1000)) < 0;
                values.put(wholeSmall ? Role.QUANTITY : Role.UNIT_PRICE, first);
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        for (Map.Entry<String, Role> c : columns.entrySet()) {
            Object v = c.getValue() == Role.DESCRIPTION ? r.description() : values.get(c.getValue());
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
            Role role = n.matches(".*(sku|code|hsn|partno|itemno).*") ? Role.CODE
                    : n.matches(".*(desc|name|item|product|particular|title|service|detail).*") ? Role.DESCRIPTION
                    : n.matches("(qty|quantity|units?|count|nos|hours|hrs)") ? Role.QUANTITY
                    : n.matches(".*(unitprice|unitcost|price|rate|mrp).*") ? Role.UNIT_PRICE
                    : n.matches(".*(discount|disc).*") ? Role.DISCOUNT
                    : n.matches(".*(tax|gst|vat).*") ? Role.TAX
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
