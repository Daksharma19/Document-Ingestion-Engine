package com.docextract.parse.pdf;

import com.docextract.document.ContentCache;
import com.docextract.document.Document;
import com.docextract.parse.model.BBox;
import com.docextract.strategy.ExtractionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a PDF once into per-page positioned lines plus the facts the planner needs (glyph health, image area).
 * The result is cached by content, so probing, planning and parsing never re-open the file.
 */
@Component
public class PdfPageReader {

    public static final String COLUMN_GAP = "   ";

    private final ContentCache cache;

    public PdfPageReader(ContentCache cache) {
        this.cache = cache;
    }

    public List<PdfPage> read(Document document) {
        return cache.get(document, "parse:pdf-pages", () -> {
            try (PDDocument pdf = Loader.loadPDF(document.bytes())) {
                LineCollector collector = new LineCollector();
                // content-stream order: sorting by position merges glyphs of neighbouring columns into one line;
                // ReadingOrder rebuilds the order from the segments' geometry instead
                collector.setSortByPosition(false);
                collector.writeText(pdf, new StringWriter());
                List<PdfPage> pages = new ArrayList<>();
                for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                    PDPage page = pdf.getPage(i);
                    PDRectangle box = page.getCropBox();
                    LineCollector.PageLines pl = collector.pages.size() > i ? collector.pages.get(i)
                            : new LineCollector.PageLines(List.of(), 0, 0);
                    Images images = images(page, box);
                    // only a page with neither text nor images needs the (cheap) ink check: blank, or vector art
                    Double ink = pl.lines().isEmpty() && images.areaRatio() == 0 ? inkRatio(pdf, i) : null;
                    pages.add(new PdfPage(i + 1, box.getWidth(), box.getHeight(), pl.lines(), pl.glyphs(),
                            pl.badGlyphs(), images.areaRatio(), ink, images.boxes()));
                }
                return List.copyOf(pages);
            } catch (IOException e) {
                throw new ExtractionException("could not read PDF " + document.filename(), e);
            }
        });
    }

    private record Images(double areaRatio, List<BBox> boxes) {
    }

    private static Images images(PDPage page, PDRectangle box) {
        try {
            ImageAreaEngine engine = new ImageAreaEngine(page, box);
            engine.processPage(page);
            double pageArea = Math.max(1, box.getWidth() * box.getHeight());
            return new Images(Math.min(1.0, engine.area / pageArea), List.copyOf(engine.boxes));
        } catch (IOException | RuntimeException e) {
            return new Images(0, List.of());
        }
    }

    /** Share of non-white pixels in an 18 DPI greyscale render (a blank page is ~0). */
    static double inkRatio(PDDocument pdf, int page) {
        try {
            java.awt.image.BufferedImage img = new org.apache.pdfbox.rendering.PDFRenderer(pdf)
                    .renderImageWithDPI(page, 18, org.apache.pdfbox.rendering.ImageType.GRAY);
            java.awt.image.Raster r = img.getRaster();
            long dark = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if (r.getSample(x, y, 0) < 200) {
                        dark++;
                    }
                }
            }
            return (double) dark / Math.max(1, (long) img.getWidth() * img.getHeight());
        } catch (IOException | RuntimeException e) {
            return 1.0; // unknown: let the planner treat it as content
        }
    }

    /** A glyph that decodes to nothing a reader could use: a sign of a broken font encoding. */
    static boolean isBadGlyph(String unicode) {
        if (unicode == null || unicode.isEmpty()) {
            return true;
        }
        for (int i = 0; i < unicode.length(); i++) {
            char c = unicode.charAt(i);
            int type = Character.getType(c);
            if (c == '�' || type == Character.PRIVATE_USE || type == Character.UNASSIGNED
                    || (Character.isISOControl(c) && !Character.isWhitespace(c))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects segments page by page: runs of glyphs on one baseline with no wide gap inside, with geometry, font
     * size and boldness. {@link com.docextract.parse.layout.ReadingOrder} turns them into ordered lines.
     */
    static final class LineCollector extends PDFTextStripper {

        record PageLines(List<TextLine> lines, int glyphs, int badGlyphs) {
        }

        final List<PageLines> pages = new ArrayList<>();
        private List<TextLine> lines;
        private List<Glyph> buffer = new ArrayList<>();
        private StringBuilder angledText = new StringBuilder();
        private boolean inAngled;
        private float lastDir;
        private BBox box;
        private float maxSize;
        private int chars;
        private int boldChars;
        private int glyphs;
        private int badGlyphs;
        private float lastEndX;

        LineCollector() throws IOException {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            lines = new ArrayList<>();
            inAngled = false;
            glyphs = 0;
            badGlyphs = 0;
            resetLine();
            super.startPage(page);
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            flush();
            pages.add(new PageLines(List.copyOf(lines), glyphs, badGlyphs));
            super.endPage(page);
        }

        /** One glyph of the current segment; {@code verbatim} pieces are whole angled runs kept as PDFBox read them. */
        private record Glyph(String text, double x0, double x1, float size, boolean space, boolean verbatim) {
        }

        @Override
        protected void writeString(String s, List<TextPosition> positions) {
            if (positions.isEmpty()) {
                return;
            }
            TextPosition first = positions.getFirst();
            float charWidth = Math.max(first.getWidthOfSpace(), first.getWidth());
            if (!Float.isNaN(lastEndX) && charWidth > 0 && !inAngled && !isAngled(first)) {
                double dx = first.getXDirAdj() - lastEndX;
                double dy = box == null ? 0 : Math.abs(first.getYDirAdj() - box.y1());
                // a wide gap, a jump back to the left, or a different baseline starts a new segment
                if (dx > 2.5f * charWidth || dx < -charWidth || dy > 0.5 * Math.max(1, first.getHeightDir())
                        || first.getDir() != lastDir) {
                    flush();
                }
            }
            boolean angled = isAngled(first);
            if (angled != inAngled) {
                // text set at an angle (not 0/90/180/270 degrees): every glyph has its own baseline, so neither
                // segment breaks nor spaces can come from geometry; keep PDFBox's reading of the run instead
                flush();
                inAngled = angled;
            }
            for (TextPosition p : positions) {
                glyphs++;
                if (isBadGlyph(p.getUnicode()) && !isSoftHyphen(p.getUnicode())) {
                    badGlyphs++;
                }
                double x0 = p.getXDirAdj();
                double y1 = p.getYDirAdj();
                double y0 = y1 - Math.max(p.getHeightDir(), 1);
                BBox b = new BBox(x0, y0, x0 + p.getWidthDirAdj(), y1);
                box = box == null ? b : box.union(b);
                maxSize = Math.max(maxSize, p.getFontSizeInPt());
                chars++;
                if (isBold(p.getFont())) {
                    boldChars++;
                }
                if (!angled) {
                    String u = p.getUnicode() == null ? "" : isSoftHyphen(p.getUnicode()) ? "-" : p.getUnicode();
                    buffer.add(new Glyph(u, x0, x0 + p.getWidthDirAdj(), p.getFontSizeInPt(), u.isBlank(), false));
                }
            }
            if (angled) {
                angledText.append(s);
                return;
            }
            TextPosition last = positions.getLast();
            lastEndX = last.getXDirAdj() + last.getWidthDirAdj();
            lastDir = last.getDir();
        }

        /**
         * Places word spaces from glyph geometry, relative to this segment's own letter spacing: a gap is a space
         * when it clearly exceeds the typical gap between letters. A fixed threshold either lost real spaces in
         * tightly set newspaper type ("derWerbemarkt") or split kerned words.
         */
        static String spaced(List<Glyph> glyphs) {
            if (glyphs.size() == 1 && glyphs.getFirst().verbatim()) {
                return glyphs.getFirst().text();
            }
            List<Double> gaps = new ArrayList<>();
            List<Float> sizes = new ArrayList<>();
            Glyph prev = null;
            for (Glyph g : glyphs) {
                if (g.space()) {
                    prev = null;
                    continue;
                }
                sizes.add(g.size());
                if (prev != null) {
                    gaps.add(g.x0() - prev.x1());
                }
                prev = g;
            }
            double size = sizes.isEmpty() ? 10 : Math.max(1, sizes.stream().sorted().toList().get(sizes.size() / 2));
            double letter = gaps.isEmpty() ? 0 : Math.max(0, gaps.stream().sorted().toList().get(gaps.size() / 2));
            double threshold = Math.max(0.08 * size, letter + 0.1 * size);
            StringBuilder out = new StringBuilder();
            prev = null;
            for (Glyph g : glyphs) {
                if (g.space()) {
                    if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
                        out.append(' ');
                    }
                    prev = null;
                    continue;
                }
                if (prev != null && g.x0() - prev.x1() > threshold && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                out.append(g.text());
                prev = g;
            }
            return out.toString();
        }

        static boolean isSoftHyphen(String u) {
            return "\uFFFE".equals(u) || "\u00AD".equals(u) || "\u0002".equals(u);
        }

        @Override
        protected void writeWordSeparator() {
            // spaces are decided from glyph gaps in spaced(); only angled text keeps PDFBox's word breaks
            if (inAngled && !angledText.isEmpty()) {
                angledText.append(' ');
            }
        }

        /**
         * The glyph's baseline is set at an angle that is not a multiple of 90 degrees. Text rotated by 90/180/270
         * is fine: PDFBox's direction-adjusted coordinates put it on a normal baseline.
         */
        static boolean isAngled(TextPosition p) {
            Matrix m = p.getTextMatrix();
            double deg = Math.abs(Math.toDegrees(Math.atan2(m.getShearY(), m.getScaleX()))) % 90;
            return deg > 3 && deg < 87;
        }

        /**
         * PDFBox's line breaks are only a hint: it breaks inside rotated words ("th" | "is") and similar runs. The
         * next glyph decides in writeString: same baseline and close by continues the segment.
         */
        @Override
        protected void writeLineSeparator() {
        }

        private void flush() {
            String t = !angledText.isEmpty() ? angledText.toString().replaceAll("\\s+", " ").strip()
                    : buffer.isEmpty() ? "" : ligatures(spaced(buffer)).strip();
            if (!t.isEmpty() && box != null) {
                lines.add(new TextLine(t, box, maxSize, chars > 0 && boldChars * 2 > chars));
            }
            resetLine();
        }

        private void resetLine() {
            buffer = new ArrayList<>();
            angledText = new StringBuilder();
            box = null;
            maxSize = 0;
            chars = 0;
            boldChars = 0;
            lastEndX = Float.NaN;
        }

        /** Typographic ligatures decode to one code point ("ﬁ"); readers and search want the letters. */
        static String ligatures(String t) {
            return t.replace("\uFB00", "ff").replace("\uFB01", "fi").replace("\uFB02", "fl")
                    .replace("\uFB03", "ffi").replace("\uFB04", "ffl").replace("\uFB05", "st").replace("\uFB06", "st");
        }

        private static boolean isBold(PDFont font) {
            if (font == null || font.getName() == null) {
                return false;
            }
            String n = font.getName().toLowerCase(Locale.ROOT);
            return n.contains("bold") || n.contains("black") || n.contains("heavy") || n.contains("semibold");
        }
    }

    /**
     * Sums the page area covered by drawn images and records each image's box (its unit square mapped through the
     * CTM) in page points with a top-left origin, the same frame as the text segments.
     */
    static final class ImageAreaEngine extends PDFGraphicsStreamEngine {
        double area;
        final List<BBox> boxes = new ArrayList<>();
        private final PDRectangle crop;

        ImageAreaEngine(PDPage page, PDRectangle crop) {
            super(page);
            this.crop = crop;
        }

        @Override
        public void drawImage(PDImage pdImage) {
            Matrix m = getGraphicsState().getCurrentTransformationMatrix();
            area += Math.abs(m.getScalingFactorX() * m.getScalingFactorY());
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] c : new double[][] {{0, 0}, {1, 0}, {0, 1}, {1, 1}}) {
                Point2D.Float p = m.transformPoint((float) c[0], (float) c[1]);
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y);
                maxY = Math.max(maxY, p.y);
            }
            double left = crop.getLowerLeftX();
            double top = crop.getUpperRightY();
            BBox b = new BBox(Math.max(0, minX - left), Math.max(0, top - maxY),
                    Math.min(crop.getWidth(), maxX - left), Math.min(crop.getHeight(), top - minY));
            if (b.x1() > b.x0() && b.y1() > b.y0()) {
                boxes.add(b);
            }
        }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { }
        @Override public void clip(int windingRule) { }
        @Override public void moveTo(float x, float y) { }
        @Override public void lineTo(float x, float y) { }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { }
        @Override public Point2D getCurrentPoint() { return new Point2D.Float(0, 0); }
        @Override public void closePath() { }
        @Override public void endPath() { }
        @Override public void strokePath() { }
        @Override public void fillPath(int windingRule) { }
        @Override public void fillAndStrokePath(int windingRule) { }
        @Override public void shadingFill(COSName shadingName) { }
    }
}
