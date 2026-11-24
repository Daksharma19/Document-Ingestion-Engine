package com.docextract.parse.tier;

import com.docextract.parse.model.BBox;
import com.docextract.parse.model.Block;
import com.docextract.parse.model.PageMethod;
import com.docextract.parse.pdf.TextLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns positioned lines (from a PDF text layer, later also from OCR) into blocks: headings from font size or
 * weight, list items from bullet markers, tables from runs of lines split into the same number of columns, and
 * paragraphs from lines that sit close together. Pure function of the lines, so every tier can share it.
 */
public final class NativePageParser {

    static final Pattern COLUMN_SPLIT = Pattern.compile("\\s{3,}");
    static final Pattern BULLET = Pattern.compile("^\\s*(?:[•●▪◦‣■□➢►·*\\-–]|\\(?\\d{1,2}[.)]|\\(?[a-zA-Z][.)])\\s+");

    private NativePageParser() {
    }

    public static List<Block> parse(List<TextLine> lines, PageMethod method) {
        if (lines.isEmpty()) {
            return List.of();
        }
        float body = bodyFontSize(lines);
        boolean boldBody = lines.stream().filter(TextLine::bold).count() * 2 > lines.size();
        Style style = new Style(body, boldBody, lines.stream().filter(TextLine::italic).count() * 2 > lines.size(), method == PageMethod.OCR);
        List<Block> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            TextLine line = lines.get(i);
            if (line.figure()) {
                out.add(Block.figure(line.text(), line.bbox(), line.confidence()));
                i++;
                continue;
            }
            List<String> cells = cells(line.text());
            if (cells.size() >= 2) {
                int j = i;
                List<List<String>> rows = new ArrayList<>();
                BBox box = null;
                while (j < lines.size() && !lines.get(j).figure() && cells(lines.get(j).text()).size() >= 2
                        && (j == i || closeBelow(lines.get(j - 1), lines.get(j), 2.2))) {
                    rows.add(cells(lines.get(j).text()));
                    box = lines.get(j).bbox().union(box);
                    j++;
                }
                if (rows.size() >= 2 && consistent(rows)) {
                    out.add(Block.table(pad(rows), box, method).withConfidence(meanConfidence(lines, i, j)));
                    i = j;
                    continue;
                }
            }
            Integer level = headingLevel(line, style);
            if (level != null) {
                StringBuilder text = new StringBuilder(line.text());
                BBox box = line.bbox();
                int j = i + 1;
                while (j < lines.size() && !lines.get(j).figure() && level.equals(headingLevel(lines.get(j), style))
                        && Math.abs(lines.get(j).fontSize() - line.fontSize()) < 0.5 && closeBelow(lines.get(j - 1), lines.get(j), 1.5)) {
                    join(text, lines.get(j).text());
                    box = lines.get(j).bbox().union(box);
                    j++;
                }
                out.add(Block.heading(collapse(text.toString()), level, box, method).withConfidence(meanConfidence(lines, i, j)));
                i = j;
                continue;
            }
            if (BULLET.matcher(line.text()).find()) {
                StringBuilder text = new StringBuilder(BULLET.matcher(line.text()).replaceFirst(""));
                BBox box = line.bbox();
                int j = i + 1;
                // wrapped continuation lines are indented past the bullet and carry no bullet of their own
                while (j < lines.size() && isContinuation(lines.get(j - 1), lines.get(j), style)
                        && lines.get(j).bbox().x0() > line.bbox().x0() + 2) {
                    join(text, lines.get(j).text());
                    box = lines.get(j).bbox().union(box);
                    j++;
                }
                out.add(Block.listItem(collapse(text.toString()), box, method).withConfidence(meanConfidence(lines, i, j)));
                i = j;
                continue;
            }
            StringBuilder text = new StringBuilder(line.text());
            BBox box = line.bbox();
            int j = i + 1;
            while (j < lines.size() && isContinuation(lines.get(j - 1), lines.get(j), style)) {
                join(text, lines.get(j).text());
                box = lines.get(j).bbox().union(box);
                j++;
            }
            out.add(Block.paragraph(collapse(text.toString()), box, method).withConfidence(meanConfidence(lines, i, j)));
            i = j;
        }
        return out;
    }

    /** Character-weighted mean confidence of lines [from, to). */
    static double meanConfidence(List<TextLine> lines, int from, int to) {
        double sum = 0;
        int chars = 0;
        for (int k = from; k < to; k++) {
            int n = Math.max(1, lines.get(k).text().length());
            sum += lines.get(k).confidence() * n;
            chars += n;
        }
        return chars == 0 ? 1.0 : sum / chars;
    }

    /** The next line belongs to the same paragraph: close below, not a heading/bullet/table row itself. */
    private static boolean isContinuation(TextLine prev, TextLine next, Style style) {
        return !next.figure() && closeBelow(prev, next, 1.7)
                && headingLevel(next, style) == null
                && !BULLET.matcher(next.text()).find()
                && cells(next.text()).size() < 2;
    }

    /**
     * {@code next} sits on the following line: its baseline is at most {@code maxLeading} font sizes below
     * {@code prev}'s (body text is set at ~1.2-1.4; a paragraph break is usually 2+).
     */
    static boolean closeBelow(TextLine prev, TextLine next, double maxLeading) {
        double delta = next.bbox().y1() - prev.bbox().y1();
        double size = Math.max(Math.max(prev.fontSize(), next.fontSize()), Math.max(prev.height(), next.height()));
        return delta > 0.3 * size && delta <= maxLeading * size;
    }

    /**
     * The page's body style. OCR boxes carry no font information and their heights vary by a few pixels: with
     * {@code strict} only clearly larger lines count as headings.
     */
    record Style(float body, boolean boldBody, boolean italicBody, boolean strict) {
    }

    static Integer headingLevel(TextLine line, Style style) {
        String t = line.text();
        if (t.length() > 100 || cells(t).size() >= 2 || BULLET.matcher(t).find()) {
            return null;
        }
        float ratio = style.body() > 0 ? line.fontSize() / style.body() : 1;
        if (style.strict()) {
            return ratio >= 1.8 ? Integer.valueOf(1) : ratio >= 1.4 ? Integer.valueOf(2) : null;
        }
        if (ratio >= 1.6) {
            return 1;
        }
        if (ratio >= 1.3) {
            return 2;
        }
        if (ratio >= 1.12 || (line.bold() && !style.boldBody() && t.length() <= 80 && !t.endsWith(".") && !t.endsWith(","))) {
            return 3;
        }
        // run-in italic subheads ("Boots Self-Locking Nut"): short, title case, no sentence punctuation
        if (line.italic() && !line.bold() && !style.italicBody() && ratio > 0.95 && t.length() <= 60
                && !t.matches(".*[.,;:]$") && titleCase(t)) {
            return 4;
        }
        return null;
    }

    /** Most words start with a capital (small words like "of", "and" don't count against it). */
    static boolean titleCase(String t) {
        String[] words = t.strip().split("\\s+");
        if (words.length == 0 || !Character.isUpperCase(words[0].charAt(0))) {
            return false;
        }
        int counted = 0;
        int upper = 0;
        for (String w : words) {
            if (w.length() <= 3 && w.chars().allMatch(Character::isLowerCase)) {
                continue;
            }
            counted++;
            if (Character.isUpperCase(w.charAt(0)) || Character.isDigit(w.charAt(0))) {
                upper++;
            }
        }
        return counted > 0 && upper * 10 >= counted * 8;
    }

    /** The font size most of the page's characters are set in. */
    static float bodyFontSize(List<TextLine> lines) {
        java.util.Map<Integer, Integer> weight = new java.util.HashMap<>();
        for (TextLine l : lines) {
            if (l.figure()) {
                continue;
            }
            weight.merge(Math.round(l.fontSize() * 2), l.text().length(), Integer::sum);
        }
        return weight.entrySet().stream().max(java.util.Map.Entry.comparingByValue())
                .map(e -> e.getKey() / 2f).orElse(0f);
    }

    static List<String> cells(String text) {
        return Arrays.stream(COLUMN_SPLIT.split(text.strip())).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** Most rows agree on the column count (header and wrapped rows may differ by one). */
    private static boolean consistent(List<List<String>> rows) {
        int max = rows.stream().mapToInt(List::size).max().orElse(0);
        long close = rows.stream().filter(r -> r.size() >= max - 1).count();
        return close * 10 >= rows.size() * 7L;
    }

    private static List<List<String>> pad(List<List<String>> rows) {
        int max = rows.stream().mapToInt(List::size).max().orElse(0);
        List<List<String>> out = new ArrayList<>();
        for (List<String> r : rows) {
            List<String> p = new ArrayList<>(r);
            while (p.size() < max) {
                p.add("");
            }
            out.add(List.copyOf(p));
        }
        return List.copyOf(out);
    }

    /** Joins a wrapped line, re-joining a word hyphenated across the break ("docu-" + "ment"). */
    private static void join(StringBuilder text, String next) {
        int n = text.length();
        if (n > 1 && text.charAt(n - 1) == '-' && Character.isLetter(text.charAt(n - 2))
                && !next.isEmpty() && Character.isLowerCase(next.charAt(0))) {
            text.setLength(n - 1);
            text.append(next);
        } else {
            text.append(' ').append(next);
        }
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }
}
