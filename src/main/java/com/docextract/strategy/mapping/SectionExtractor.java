package com.docextract.strategy.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads content that sits <em>under</em> a heading rather than after a "Label:" — summaries, notes, terms,
 * achievements, findings, clauses. Works for any document with headings on their own line:
 * <ul>
 *   <li>a section starts at a line that is just the heading (e.g. "SUMMARY", "Terms and Conditions", "Findings:");</li>
 *   <li>it ends at the next heading-like line: all-caps words, or a short line ending in ":";</li>
 *   <li>for a string field the section's lines are one paragraph (hyphenated line breaks re-joined);</li>
 *   <li>for an array of strings each bullet is one item (wrapped lines joined), or each line if there are no bullets.</li>
 * </ul>
 */
public final class SectionExtractor {

    private SectionExtractor() {
    }

    private static final Pattern BULLET = Pattern.compile("^[\\u2022\\u25CF\\u25AA\\u25E6\\u2023\\u2043\\u2219\\u00B7\\uFFFD*\\-–—▪►✓✔]\\s*");
    private static final Pattern CAPS_HEADING = Pattern.compile("^[A-Z][A-Z0-9 &/,'()-]{2,60}$");
    private static final Pattern KEY_VALUE = Pattern.compile("^[A-Za-z][A-Za-z #./&'()-]{0,30}\\s?:\\s*\\S");
    private static final Pattern COLON_HEADING =Pattern.compile("^[A-Z][A-Za-z &/'()-]{2,40}:$");

    /** The section under the first heading matching any of the labels, as one paragraph. */
    public static Optional<String> paragraph(String text, Set<String> labels) {
        return lines(text, labels).map(SectionExtractor::join).filter(s -> !s.isBlank());
    }

    /** The section under the first heading matching any of the labels, as a list of items. */
    public static Optional<List<String>> items(String text, Set<String> labels) {
        Optional<List<String>> lines = lines(text, labels);
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        boolean bulleted = lines.get().stream().anyMatch(l -> BULLET.matcher(l).find());
        List<String> out = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines.get()) {
            if (!bulleted) {
                out.add(line);
                continue;
            }
            if (BULLET.matcher(line).find()) {
                if (!current.isEmpty()) {
                    out.add(join(current));
                }
                current = new ArrayList<>(List.of(BULLET.matcher(line).replaceFirst("")));
            } else {
                current.add(line); // wrapped continuation of the current bullet
            }
        }
        if (!current.isEmpty()) {
            out.add(join(current));
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    private static Optional<List<String>> lines(String text, Set<String> labels) {
        String[] all = text.split("\\R");
        for (int i = 0; i < all.length; i++) {
            if (isHeadingFor(all[i], labels)) {
                List<String> body = new ArrayList<>();
                for (int j = i + 1; j < all.length; j++) {
                    String t = all[j].strip();
                    if (t.isEmpty()) {
                        if (!body.isEmpty() && j + 1 < all.length && isHeading(all[j + 1].strip())) {
                            break;
                        }
                        continue;
                    }
                    if (isHeading(t)) {
                        break;
                    }
                    body.add(t);
                }
                // "Terms:" followed by "Order ID : 123" is an empty label, not a section about order ids.
                boolean colonHeading = all[i].strip().endsWith(":");
                if (!body.isEmpty() && !(colonHeading && KEY_VALUE.matcher(body.getFirst()).find())) {
                    return Optional.of(body);
                }
            }
        }
        return Optional.empty();
    }

    /** The line is only the heading, or the heading followed by " & Something" (e.g. "ACHIEVEMENTS & CERTIFICATIONS"). */
    static boolean isHeadingFor(String line, Set<String> labels) {
        String t = line.strip().replaceAll(":$", "").toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.length() > 60) {
            return false;
        }
        for (String label : labels) {
            String l = label.toLowerCase(Locale.ROOT).strip();
            if (t.equals(l) || t.startsWith(l + " & ") || t.startsWith(l + " and ") || t.endsWith(" & " + l) || t.endsWith(" and " + l)) {
                return true;
            }
        }
        return false;
    }

    static boolean isHeading(String t) {
        if (CAPS_HEADING.matcher(t).matches() && t.chars().filter(Character::isLetter).count() >= 3) {
            return t.split("\\s+").length <= 6;
        }
        return COLON_HEADING.matcher(t).matches();
    }

    /** Lines into one paragraph; "rela-" + "tional" becomes "relational". */
    static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (sb.length() > 0) {
                boolean hyphenBreak = sb.length() > 1 && sb.charAt(sb.length() - 1) == '-'
                        && Character.isLetter(sb.charAt(sb.length() - 2)) && !l.isEmpty() && Character.isLowerCase(l.charAt(0));
                if (hyphenBreak) {
                    sb.setLength(sb.length() - 1);
                } else {
                    sb.append(' ');
                }
            }
            sb.append(l.strip());
        }
        return sb.toString().replaceAll("\\s+", " ").strip();
    }
}
