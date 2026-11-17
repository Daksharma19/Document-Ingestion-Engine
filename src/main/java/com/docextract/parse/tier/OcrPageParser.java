package com.docextract.parse.tier;

import com.docextract.document.Document;
import com.docextract.parse.layout.ReadingOrder;
import com.docextract.parse.model.BBox;
import com.docextract.parse.model.Block;
import com.docextract.parse.model.PageMethod;
import com.docextract.parse.pdf.TextLine;
import com.docextract.strategy.TierNotConfiguredException;
import com.docextract.strategy.ocr.PythonOcrClient;
import com.docextract.strategy.ocr.PythonOcrClient.OcrLine;
import com.docextract.strategy.ocr.PythonOcrClient.OcrPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * OCRs only what the planner sent here, one service call per document: whole pages (scans, broken text layers) or
 * just the image regions of text pages. Recognised boxes become the same {@link TextLine} segments a text layer
 * gives (pixels converted to PDF points), so {@link ReadingOrder} and {@link NativePageParser} treat scans and
 * digital pages alike.
 */
@Component
public class OcrPageParser {

    /** What OCR produced for one page; {@code text} is the plain reading-order text (for quality checks). */
    public record OcrResult(List<Block> blocks, double confidence, String text) {
    }

    private final PythonOcrClient client;
    private final int dpi;

    public OcrPageParser(PythonOcrClient client, @Value("${docextract.ocr.dpi:300}") int dpi) {
        this.client = client;
        this.dpi = dpi;
    }

    public int dpi() {
        return dpi;
    }

    public Map<Integer, OcrResult> parse(Document document, List<Integer> pages) {
        return parse(document, pages, dpi);
    }

    /**
     * @param pages 1-based PDF page numbers; empty for an image file (its one page)
     * @return results keyed by page number (1 for an image)
     */
    public Map<Integer, OcrResult> parse(Document document, List<Integer> pages, int atDpi) {
        requireService();
        List<OcrPage> read = client.recognisePages(document, atDpi, pages);
        Map<Integer, OcrResult> out = new LinkedHashMap<>();
        for (int i = 0; i < read.size(); i++) {
            int index = pages.isEmpty() ? i + 1 : pages.get(i);
            OcrPage page = read.get(i);
            List<TextLine> lines = ReadingOrder.order(toSegments(page));
            String text = String.join("\n", lines.stream().map(TextLine::text).toList());
            out.put(index, new OcrResult(NativePageParser.parse(lines, PageMethod.OCR),
                    Math.round(page.meanConfidence() * 1000) / 1000.0, text));
        }
        return out;
    }

    /**
     * OCR of image regions on text pages. Each region with readable text becomes one figure segment (its lines in
     * reading order, one per line) to be merged with the page's own text.
     */
    public Map<Integer, List<TextLine>> figures(Document document, Map<Integer, List<BBox>> regions) {
        requireService();
        TreeMap<Integer, List<double[]>> request = new TreeMap<>();
        regions.forEach((page, boxes) -> request.put(page,
                boxes.stream().map(b -> new double[] {b.x0(), b.y0(), b.x1(), b.y1()}).toList()));
        List<OcrPage> read = client.recogniseRegions(document, dpi, request);
        Map<Integer, List<TextLine>> out = new LinkedHashMap<>();
        List<Integer> order = new ArrayList<>(request.keySet());
        for (int i = 0; i < read.size() && i < order.size(); i++) {
            int index = order.get(i);
            List<TextLine> segments = toSegments(read.get(i));
            List<TextLine> figures = new ArrayList<>();
            for (BBox region : regions.get(index)) {
                List<TextLine> inside = segments.stream().filter(s -> contains(region, s.bbox())).toList();
                if (inside.isEmpty()) {
                    continue;
                }
                List<TextLine> lines = ReadingOrder.order(inside);
                String text = String.join("\n", lines.stream().map(l -> l.text().replaceAll("\\s{3,}", "  ")).toList());
                double conf = NativePageParser.meanConfidence(lines, 0, lines.size());
                // photos produce a few low-confidence specks; only keep regions that read as text
                if (text.replaceAll("[^\\p{L}\\p{N}]", "").length() >= 3 && conf >= 0.6) {
                    figures.add(TextLine.figure(text, region, conf));
                }
            }
            out.put(index, figures);
        }
        return out;
    }

    private void requireService() {
        if (!client.isAvailable()) {
            throw new TierNotConfiguredException("OCR service not reachable at " + client.baseUrl());
        }
    }

    private static boolean contains(BBox region, BBox b) {
        double cx = (b.x0() + b.x1()) / 2;
        double cy = (b.y0() + b.y1()) / 2;
        return cx >= region.x0() - 2 && cx <= region.x1() + 2 && cy >= region.y0() - 2 && cy <= region.y1() + 2;
    }

    /** Detection boxes (one per piece of a line) as segments; the service's own lines when it sent no boxes. */
    static List<TextLine> toSegments(OcrPage page) {
        double scale = 72.0 / page.dpi();
        List<OcrLine> source = page.boxes().isEmpty() ? page.lines() : page.boxes();
        List<TextLine> lines = new ArrayList<>(source.size());
        for (OcrLine l : source) {
            if (l.text().isBlank()) {
                continue;
            }
            BBox box = new BBox(l.x0() * scale, l.y0() * scale, l.x1() * scale, l.y1() * scale);
            // a detection box spans ascenders to descenders: close to the font size
            lines.add(new TextLine(l.text(), box, (float) (l.height() * scale), false, l.confidence()));
        }
        return lines;
    }
}
