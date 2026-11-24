package com.docextract.parse.layout;

import com.docextract.parse.model.BBox;
import com.docextract.parse.pdf.PdfPageReader;
import com.docextract.parse.pdf.TextLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Puts text segments (pieces of a visual line with no wide gap inside) into reading order with a recursive XY-cut,
 * then joins the segments of each row in a region into one line, wide gaps written as
 * {@link PdfPageReader#COLUMN_GAP}.
 * <ul>
 *   <li>A vertical white channel running through a whole region, with real text on both sides that fills most of
 *   the region's height, is a column gutter: read the left side fully, then the right.</li>
 *   <li>Otherwise a horizontal white band at least half a line high splits the region into bands read top to bottom
 *   (a title above two columns, a footer below them).</li>
 *   <li>A channel between short cells (numbers, labels) is a table, not a gutter: those rows stay together so the
 *   table survives as rows.</li>
 * </ul>
 * Shared by the text layer and OCR, so both produce the same structure.
 */
public final class ReadingOrder {

    /** Below this median segment length (characters) a side of a channel looks like table cells, not prose. */
    static final int MIN_COLUMN_TEXT = 12;

    private ReadingOrder() {
    }

    public static List<TextLine> order(List<TextLine> segments) {
        if (segments.isEmpty()) {
            return List.of();
        }
        double unit = medianSize(segments);
        List<TextLine> out = new ArrayList<>();
        cut(segments, unit, out, 0);
        return out;
    }

    private static void cut(List<TextLine> segs, double unit, List<TextLine> out, int depth) {
        if (segs.size() > 1 && depth < 40) {
            List<List<TextLine>> columns = verticalSplit(segs, unit, true);
            if (columns == null) {
                List<List<TextLine>> bands = horizontalSplit(segs, unit);
                if (bands.size() > 1) {
                    for (List<TextLine> b : bands) {
                        cut(b, unit, out, depth + 1);
                    }
                    return;
                }
                columns = verticalSplit(segs, unit, false);
            }
            if (columns != null) {
                for (List<TextLine> c : columns) {
                    cut(c, unit, out, depth + 1);
                }
                return;
            }
        }
        out.addAll(rows(segs));
    }

    /**
     * The widest vertical channel crossing every segment of the region, when both sides read as text columns.
     * With {@code tall}, both sides must also cover most of the region's height (true columns, not a caption
     * beside a heading), which lets a gutter win over the small horizontal gaps that aligned columns share.
     */
    private static List<List<TextLine>> verticalSplit(List<TextLine> segs, double unit, boolean tall) {
        List<TextLine> byX = new ArrayList<>(segs);
        byX.sort(Comparator.comparingDouble(s -> s.bbox().x0()));
        double reach = byX.getFirst().bbox().x1();
        double bestGap = 0;
        int bestAt = -1;
        for (int i = 1; i < byX.size(); i++) {
            double gap = byX.get(i).bbox().x0() - reach;
            if (gap > bestGap) {
                bestGap = gap;
                bestAt = i;
            }
            reach = Math.max(reach, byX.get(i).bbox().x1());
        }
        if (bestAt < 0 || bestGap < Math.max(4, 0.6 * unit)) {
            return null;
        }
        List<TextLine> left = byX.subList(0, bestAt);
        List<TextLine> right = byX.subList(bestAt, byX.size());
        if (!readsAsColumn(left) || !readsAsColumn(right)) {
            return null;
        }
        if (tall) {
            double top = segs.stream().mapToDouble(s -> s.bbox().y0()).min().orElse(0);
            double bottom = segs.stream().mapToDouble(s -> s.bbox().y1()).max().orElse(0);
            double h = Math.max(1, bottom - top);
            if (span(left) < 0.5 * h || span(right) < 0.5 * h) {
                return null;
            }
        }
        return List.of(new ArrayList<>(left), new ArrayList<>(right));
    }

    /** Prose, not table cells: several lines, or long ones. */
    private static boolean readsAsColumn(List<TextLine> side) {
        int[] lengths = side.stream().mapToInt(s -> s.text().strip().length()).sorted().toArray();
        int median = lengths[lengths.length / 2];
        return median >= MIN_COLUMN_TEXT;
    }

    private static double span(List<TextLine> side) {
        double top = side.stream().mapToDouble(s -> s.bbox().y0()).min().orElse(0);
        double bottom = side.stream().mapToDouble(s -> s.bbox().y1()).max().orElse(0);
        return bottom - top;
    }

    /** Splits at horizontal white bands at least half a text line high that cross the whole region. */
    private static List<List<TextLine>> horizontalSplit(List<TextLine> segs, double unit) {
        List<TextLine> byY = new ArrayList<>(segs);
        byY.sort(Comparator.comparingDouble(s -> s.bbox().y0()));
        List<List<TextLine>> bands = new ArrayList<>();
        List<TextLine> current = new ArrayList<>();
        double reach = Double.NEGATIVE_INFINITY;
        for (TextLine s : byY) {
            if (!current.isEmpty() && s.bbox().y0() - reach >= 0.5 * unit) {
                bands.add(current);
                current = new ArrayList<>();
            }
            current.add(s);
            reach = Math.max(reach, s.bbox().y1());
        }
        bands.add(current);
        return bands;
    }

    /** Groups a leaf region's segments into rows (vertical overlap) and joins each row left to right. */
    static List<TextLine> rows(List<TextLine> segs) {
        List<TextLine> byY = new ArrayList<>(segs);
        byY.sort(Comparator.comparingDouble((TextLine s) -> (s.bbox().y0() + s.bbox().y1()) / 2)
                .thenComparingDouble(s -> s.bbox().x0()));
        List<List<TextLine>> rows = new ArrayList<>();
        for (TextLine s : byY) {
            if (s.figure()) {
                rows.add(new ArrayList<>(List.of(s))); // a figure never shares a row with text
                continue;
            }
            List<TextLine> best = null;
            double bestOverlap = 0;
            for (int i = Math.max(0, rows.size() - 3); i < rows.size(); i++) {
                List<TextLine> row = rows.get(i);
                if (row.getFirst().figure()) {
                    continue;
                }
                double top = row.stream().mapToDouble(r -> r.bbox().y0()).min().orElse(0);
                double bottom = row.stream().mapToDouble(r -> r.bbox().y1()).max().orElse(0);
                double overlap = Math.min(bottom, s.bbox().y1()) - Math.max(top, s.bbox().y0());
                double need = 0.5 * Math.min(bottom - top, s.height());
                if (overlap >= need && overlap > bestOverlap) {
                    best = row;
                    bestOverlap = overlap;
                }
            }
            if (best == null) {
                rows.add(new ArrayList<>(List.of(s)));
            } else {
                best.add(s);
            }
        }
        attachCellLines(rows);
        List<TextLine> out = new ArrayList<>(rows.size());
        for (List<TextLine> row : rows) {
            out.add(join(cellOrder(row)));
        }
        return out;
    }

    /**
     * A table cell with two lines ("Web Design" over "This is a sample description...") sits half a line above and
     * below the rest of its row, so neither line overlaps the row enough to join it. A row that touches a
     * neighbouring table row (three or more cells) vertically, and only fills that row's empty columns, is part of it.
     */
    static void attachCellLines(List<List<TextLine>> rows) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < rows.size() && !changed; i++) {
                List<TextLine> row = rows.get(i);
                for (int t : new int[] {i + 1, i - 1}) {
                    if (t < 0 || t >= rows.size() || rows.get(t).size() < 3 || row.size() >= rows.get(t).size()
                            || !fitsInto(row, rows.get(t))) {
                        continue;
                    }
                    rows.get(t).addAll(row);
                    rows.remove(i);
                    changed = true;
                    break;
                }
            }
        }
    }

    private static boolean fitsInto(List<TextLine> row, List<TextLine> table) {
        double top = table.stream().mapToDouble(r -> r.bbox().y0()).min().orElse(0);
        double bottom = table.stream().mapToDouble(r -> r.bbox().y1()).max().orElse(0);
        double left = table.stream().mapToDouble(r -> r.bbox().x0()).min().orElse(0);
        double right = table.stream().mapToDouble(r -> r.bbox().x1()).max().orElse(0);
        for (TextLine s : row) {
            if (s.figure() || s.height() > 1.5 * (bottom - top)) {
                return false;
            }
            // touches the row vertically, inside its horizontal extent, and collides with none of its cells
            if (Math.min(bottom, s.bbox().y1()) - Math.max(top, s.bbox().y0()) <= 0
                    || s.bbox().x0() < left || s.bbox().x0() > right) {
                return false;
            }
            for (TextLine c : table) {
                // sharing a column is fine only when stacked above/below (the other line of the same cell)
                if (Math.min(c.bbox().x1(), s.bbox().x1()) - Math.max(c.bbox().x0(), s.bbox().x0()) > 0
                        && Math.min(c.bbox().y1(), s.bbox().y1()) - Math.max(c.bbox().y0(), s.bbox().y0()) > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Left to right by column; lines stacked in one column (a multi-line cell) top to bottom. */
    static List<TextLine> cellOrder(List<TextLine> row) {
        List<TextLine> byX = new ArrayList<>(row);
        byX.sort(Comparator.comparingDouble(s -> s.bbox().x0()));
        List<List<TextLine>> cols = new ArrayList<>();
        double reach = Double.NEGATIVE_INFINITY;
        for (TextLine s : byX) {
            if (cols.isEmpty() || s.bbox().x0() >= reach) {
                cols.add(new ArrayList<>());
            }
            cols.getLast().add(s);
            reach = Math.max(reach, s.bbox().x1());
        }
        List<TextLine> out = new ArrayList<>();
        for (List<TextLine> c : cols) {
            if (c.size() > 1 && c.stream().anyMatch(a -> c.stream().anyMatch(b -> a != b
                    && Math.min(a.bbox().y1(), b.bbox().y1()) - Math.max(a.bbox().y0(), b.bbox().y0()) > 0.5 * Math.min(a.height(), b.height())))) {
                out.addAll(c); // same visual line, already left to right
            } else {
                c.sort(Comparator.comparingDouble(s -> s.bbox().y0()));
                out.addAll(c);
            }
        }
        return out;
    }

    private static TextLine join(List<TextLine> row) {
        if (row.size() == 1) {
            return row.getFirst();
        }
        StringBuilder text = new StringBuilder();
        BBox box = null;
        float size = 0;
        int chars = 0;
        int boldChars = 0;
        int italicChars = 0;
        double conf = 0;
        TextLine prev = null;
        for (TextLine s : row) {
            if (prev != null) {
                double gap = s.bbox().x0() - prev.bbox().x1();
                double charWidth = Math.max(1, Math.max(prev.fontSize(), s.fontSize()) * 0.5);
                text.append(gap > 2.5 * charWidth ? PdfPageReader.COLUMN_GAP : " ");
            }
            text.append(s.text());
            box = s.bbox().union(box);
            size = Math.max(size, s.fontSize());
            int n = Math.max(1, s.text().length());
            chars += n;
            if (s.bold()) {
                boldChars += n;
            }
            if (s.italic()) {
                italicChars += n;
            }
            conf += s.confidence() * n;
            prev = s;
        }
        return new TextLine(text.toString(), box, size, boldChars * 2 > chars, conf / chars, false,
                italicChars * 2 > chars);
    }

    private static double medianSize(List<TextLine> segs) {
        double[] h = segs.stream().mapToDouble(s -> Math.max(s.fontSize(), s.height())).sorted().toArray();
        return Math.max(1, h[h.length / 2]);
    }
}
