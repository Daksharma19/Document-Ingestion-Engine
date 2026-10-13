package com.docextract.validation;

import java.util.List;

/**
 * One specific problem with an extraction: which field, which rule, and a human-readable reason.
 * {@code relatedFields} lists every top-level field the failure implicates (a cross-field rule
 * such as "sum(lineItems[].amount) == subtotal" implicates both sides).
 */
public record Violation(String field, String rule, String message, List<String> relatedFields) {

    public Violation {
        relatedFields = relatedFields == null || relatedFields.isEmpty()
                ? (field == null ? List.of() : List.of(topLevel(field)))
                : List.copyOf(relatedFields);
    }

    public Violation(String field, String rule, String message) {
        this(field, rule, message, null);
    }

    /** "lineItems[0].amount" and "lineItems.0.amount" both belong to top-level field "lineItems". */
    public static String topLevel(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        int cut = p.length();
        for (char c : new char[]{'.', '[', '/'}) {
            int i = p.indexOf(c);
            if (i >= 0) {
                cut = Math.min(cut, i);
            }
        }
        return p.substring(0, cut);
    }
}
