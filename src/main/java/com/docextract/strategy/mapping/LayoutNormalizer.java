package com.docextract.strategy.mapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rewrites two common form layouts into plain "Label: value" lines, so label matching works on them.
 * Text extraction marks column breaks with 3+ spaces (see TextExtractor / Tesseract's preserved spacing).
 * <ul>
 *   <li><b>Header row over value row</b>:
 *       <pre>SALESPERSON   SHIPPING METHOD   DUE DATE
 * Kendall Collins   Air express   11/12/2026</pre>
 *       becomes "SALESPERSON: Kendall Collins", "SHIPPING METHOD: Air express", "DUE DATE: 11/12/2026".</li>
 *   <li><b>Block label</b>: a cell that is only "From:" takes the cell in the same column on the next line
 *       ("From:" over "DEMO - Sliced Invoices").</li>
 * </ul>
 * Item-table headers (qty/price/amount...) are left alone; the line-item reader needs them.
 */
public final class LayoutNormalizer {

    private LayoutNormalizer() {
    }

    static final Pattern CELL_GAP = Pattern.compile("\\s{3,}");
    private static final Pattern LABEL_CELL = Pattern.compile("[A-Za-z][A-Za-z #./&()'-]{0,40}");

    public static List<String> cells(String line) {
        String t = line.strip();
        return t.isEmpty() ? List.of() : Arrays.asList(CELL_GAP.split(t));
    }

    public static String normalise(String text) {
        String[] lines = text.split("\\R", -1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            List<String> header = cells(lines[i]);
            int next = nextNonBlank(lines, i + 1);
            if (next >= 0 && isHeaderRow(header)) {
                List<String> values = cells(lines[next]);
                if (values.size() == header.size() && !isHeaderRow(values)) {
                    for (int c = 0; c < header.size(); c++) {
                        out.add(header.get(c) + ": " + values.get(c));
                    }
                    i = next;
                    continue;
                }
            }
            out.add(lines[i]);
            // Block labels: emit "Label: value" in addition, keeping the original lines.
            if (next >= 0) {
                List<String> below = cells(lines[next]);
                for (int c = 0; c < header.size() && c < below.size(); c++) {
                    String cell = header.get(c).strip();
                    String value = below.get(c).strip();
                    if (cell.endsWith(":") && LABEL_CELL.matcher(cell.substring(0, cell.length() - 1)).matches()
                            && !value.endsWith(":") && !value.contains(":")) {
                        out.add(cell + " " + value);
                    }
                }
            }
        }
        return String.join("\n", out);
    }

    /** Two or more cells, each a short caption with no digits and no colon, and not an item-table header. */
    static boolean isHeaderRow(List<String> cells) {
        if (cells.size() < 2) {
            return false;
        }
        for (String c : cells) {
            if (!LABEL_CELL.matcher(c.strip()).matches() || c.contains(":") || c.strip().split("\\s+").length > 4) {
                return false;
            }
        }
        return !LineItemExtractor.isTableHeader(String.join(" ", cells));
    }

    private static int nextNonBlank(String[] lines, int from) {
        for (int j = from; j < lines.length; j++) {
            if (!lines[j].isBlank()) {
                return j;
            }
        }
        return -1;
    }
}
