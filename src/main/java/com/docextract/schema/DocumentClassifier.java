package com.docextract.schema;

import com.docextract.schema.SchemaCatalog.SchemaPreset;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Picks the built-in schema that best fits a document's text. Each preset lists indicative phrases; a phrase
 * scores 1 anywhere in the text and 3 in the first lines (where the document names itself: "TAX INVOICE",
 * "Statement of Account", "Salary Slip"), and 9 when one of the first three lines starts with it (the document's
 * title). Each score is divided by the number of presets that list the phrase: "invoice" says little when three
 * invoice types share it, "gstin" names one. Below a minimum score the document is "generic".
 */
@Component
public class DocumentClassifier {

    /** @param score the winning score (0 for the generic fallback) */
    public record Classification(SchemaPreset preset, double score) {
    }

    static final int HEAD_LINES = 8;
    static final int TITLE_LINES = 3;
    static final double MIN_SCORE = 2.0;

    private final SchemaCatalog catalog;

    public DocumentClassifier(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    public Classification classify(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String[] lines = lower.split("\\R");
        StringBuilder head = new StringBuilder();
        java.util.List<String> titles = new java.util.ArrayList<>();
        for (int i = 0, kept = 0; i < lines.length && kept < HEAD_LINES; i++) {
            if (!lines[i].isBlank()) {
                head.append(lines[i]).append('\n');
                if (kept < TITLE_LINES) {
                    titles.add(lines[i].strip());
                }
                kept++;
            }
        }
        java.util.Map<String, Long> sharedBy = new java.util.HashMap<>();
        catalog.all().forEach(p -> p.keywords().forEach(k -> sharedBy.merge(k.toLowerCase(Locale.ROOT), 1L, Long::sum)));
        SchemaPreset best = null;
        double bestScore = 0;
        for (SchemaPreset p : catalog.all()) {
            double score = 0;
            for (String k : p.keywords()) {
                String key = k.toLowerCase(Locale.ROOT);
                Pattern kw = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(key) + "(?![a-z0-9])");
                double weight = 1.0 / sharedBy.get(key);
                if (titles.stream().anyMatch(t -> kw.matcher(t).lookingAt())) {
                    score += 9 * weight;
                } else if (kw.matcher(head).find()) {
                    score += 3 * weight;
                } else if (kw.matcher(lower).find()) {
                    score += weight;
                }
            }
            if (score > bestScore) {
                best = p;
                bestScore = score;
            }
        }
        if (best == null || bestScore < MIN_SCORE) {
            return new Classification(catalog.find("generic").orElse(catalog.all().getLast()), 0);
        }
        return new Classification(best, bestScore);
    }
}
