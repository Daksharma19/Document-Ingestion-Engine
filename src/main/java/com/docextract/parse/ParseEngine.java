package com.docextract.parse;

import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.document.DocumentTypeDetector.DetectedType;
import com.docextract.parse.layout.ReadingOrder;
import com.docextract.parse.model.BBox;
import com.docextract.parse.model.Block;
import com.docextract.parse.model.PageMethod;
import com.docextract.parse.model.PageProbe;
import com.docextract.parse.model.ParsedDocument;
import com.docextract.parse.model.ParsedPage;
import com.docextract.parse.pdf.PdfPage;
import com.docextract.parse.pdf.PdfPageReader;
import com.docextract.parse.pdf.TextLine;
import com.docextract.parse.plan.PagePlanner;
import com.docextract.parse.quality.TextQuality;
import com.docextract.parse.tier.NativePageParser;
import com.docextract.parse.tier.OcrPageParser;
import com.docextract.parse.tier.OcrPageParser.OcrResult;
import com.docextract.parse.tier.OfficeParser;
import com.docextract.strategy.ExtractionException;
import com.docextract.strategy.TierNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The adaptive parser. Every page is probed and planned on its own, read with the cheapest method that fits, then
 * checked; only pages whose reading fails a check are read again by a dearer method, and the better reading wins.
 * <ol>
 *   <li>Text pages: the text layer (cost 1). Images on them that carry no text layer are OCR'd as regions only
 *   (diagram labels, chart axes), at a cost proportional to their area.</li>
 *   <li>Scans, image files, broken text layers: OCR of the whole page (cost 20).</li>
 *   <li>A text layer that decodes to letters that spell nothing is confirmed with OCR; the reading with more real
 *   words wins.</li>
 *   <li>An OCR reading with low confidence is re-read at a higher resolution; the more confident reading wins.</li>
 * </ol>
 * Each page reports its method, the reason, its confidence and its cost.
 */
@Service
public class ParseEngine {

    private static final Logger log = LoggerFactory.getLogger(ParseEngine.class);

    private final DocumentTypeDetector detector;
    private final PdfPageReader pdfReader;
    private final PagePlanner planner;
    private final OfficeParser officeParser;
    private final OcrPageParser ocr;
    private final double lowOcrConfidence;
    private final int retryDpi;
    private final boolean regionOcr;

    @Autowired
    public ParseEngine(DocumentTypeDetector detector, PdfPageReader pdfReader, PagePlanner planner,
                       OfficeParser officeParser, OcrPageParser ocr,
                       @Value("${docextract.parse.low-ocr-confidence:0.85}") double lowOcrConfidence,
                       @Value("${docextract.parse.ocr-retry-dpi:450}") int retryDpi,
                       @Value("${docextract.parse.region-ocr:true}") boolean regionOcr) {
        this.detector = detector;
        this.pdfReader = pdfReader;
        this.planner = planner;
        this.officeParser = officeParser;
        this.ocr = ocr;
        this.lowOcrConfidence = lowOcrConfidence;
        this.retryDpi = retryDpi;
        this.regionOcr = regionOcr;
    }

    public ParseEngine(DocumentTypeDetector detector, PdfPageReader pdfReader, PagePlanner planner,
                       OfficeParser officeParser, OcrPageParser ocr) {
        this(detector, pdfReader, planner, officeParser, ocr, 0.85, 450, true);
    }

    /** Per-request switches. {@code figures}: OCR the text inside images on text pages. */
    public record Options(boolean figures) {
        public static final Options DEFAULT = new Options(true);
    }

    public ParsedDocument parse(Document document) {
        return parse(document, Options.DEFAULT);
    }

    public ParsedDocument parse(Document document, Options options) {
        long start = System.nanoTime();
        DetectedType type = detector.detect(document);
        try {
            List<ParsedPage> pages = switch (type.kind()) {
                case PDF -> parsePdf(document, options);
                case IMAGE -> List.of(parseImage(document));
                case OFFICE_OR_TEXT -> List.of(new ParsedPage(1, PageMethod.NATIVE, "structured file (" + type.mediaType() + ")",
                        false, null, PageMethod.NATIVE.costUnits(), null, officeParser.parse(document)));
                case UNKNOWN -> throw new ExtractionException("unrecognised file type " + type.mediaType(), null);
            };
            int cost = pages.stream().mapToInt(ParsedPage::costUnits).sum();
            return new ParsedDocument(document.filename(), type.mediaType(), type.kind().name(), "OK", null, pages,
                    MarkdownWriter.write(pages), cost, 0).withElapsed(elapsedMs(start));
        } catch (ExtractionException e) {
            log.warn("parse failed for {}", document.filename(), e);
            String cause = e.getCause() != null && e.getCause().getMessage() != null ? ": " + e.getCause().getMessage() : "";
            return ParsedDocument.error(document.filename(), type.mediaType(), type.kind().name(), e.getMessage() + cause)
                    .withElapsed(elapsedMs(start));
        }
    }

    /** What the planner decided for one PDF page, plus what the text layer gave. */
    private record PagePlan(PdfPage page, PageProbe probe, PagePlanner.Plan plan, List<BBox> regions) {
    }

    private List<ParsedPage> parsePdf(Document document, Options options) {
        List<PagePlan> plans = new ArrayList<>();
        for (PdfPage page : pdfReader.read(document)) {
            PageProbe probe = probe(page);
            PagePlanner.Plan plan = planner.plan(probe);
            List<BBox> regions = plan.method() == PageMethod.NATIVE && regionOcr && options.figures()
                    ? imageRegions(page) : List.of();
            plans.add(new PagePlan(page, probe, plan, regions));
        }

        // one OCR call for every page that needs (or must be checked by) whole-page OCR
        List<Integer> ocrPages = plans.stream().filter(p -> p.plan().method() == PageMethod.OCR || p.plan().verify())
                .map(p -> p.page().index()).toList();
        Map<Integer, OcrResult> ocrRead = ocrPages.isEmpty() ? Map.of() : orNull(() -> ocr.parse(document, ocrPages));
        // and one for the image regions of text pages
        Map<Integer, List<BBox>> regionRequest = new LinkedHashMap<>();
        plans.stream().filter(p -> !p.regions().isEmpty() && !p.plan().verify())
                .forEach(p -> regionRequest.put(p.page().index(), p.regions()));
        Map<Integer, List<TextLine>> figures = regionRequest.isEmpty() ? Map.of()
                : orNull(() -> ocr.figures(document, regionRequest));
        Map<Integer, OcrResult> retried = retryLowConfidence(document, plans, ocrRead);

        List<ParsedPage> out = new ArrayList<>();
        for (PagePlan p : plans) {
            int index = p.page().index();
            if (p.plan().method() == PageMethod.OCR) {
                out.add(ocrPage(index, p.plan().reason(), p.probe(), ocrRead, retried));
            } else if (p.plan().verify()) {
                out.add(verified(p, ocrRead == null ? null : ocrRead.get(index)));
            } else {
                out.add(nativePage(p, figures == null ? null : figures.get(index)));
            }
        }
        return out;
    }

    /** A text page, with the text OCR found in its images merged in reading order. */
    private ParsedPage nativePage(PagePlan p, List<TextLine> figureText) {
        List<TextLine> segments = new ArrayList<>(p.page().lines());
        String reason = p.plan().reason();
        int cost = PageMethod.NATIVE.costUnits();
        if (!p.regions().isEmpty()) {
            if (figureText == null) {
                reason += "; " + p.regions().size() + " image region(s) not OCR'd (OCR service unavailable)";
            } else {
                segments.addAll(figureText);
                cost += regionCost(p);
                reason += "; OCR'd " + p.regions().size() + " image region(s)"
                        + (figureText.isEmpty() ? " (no text found)" : ", " + figureText.size() + " with text");
            }
        }
        List<Block> blocks = NativePageParser.parse(ReadingOrder.order(segments), PageMethod.NATIVE);
        return new ParsedPage(p.page().index(), PageMethod.NATIVE, reason, false, 1.0, cost, p.probe(), blocks);
    }

    /** A text layer that does not read as language, checked against OCR: the reading with more real words wins. */
    private ParsedPage verified(PagePlan p, OcrResult ocrResult) {
        ParsedPage textLayer = nativePage(new PagePlan(p.page(), p.probe(), p.plan(), List.of()), null);
        if (ocrResult == null) {
            return withReason(textLayer, p.plan().reason() + "; not checked (OCR service unavailable)");
        }
        TextQuality.Score fromOcr = TextQuality.score(ocrResult.text());
        double nativeRatio = p.probe().functionWordRatio() == null ? 0 : p.probe().functionWordRatio();
        double ocrRatio = fromOcr.functionWordRatio() == null ? 0 : fromOcr.functionWordRatio();
        int cost = PageMethod.NATIVE.costUnits() + PageMethod.OCR.costUnits();
        if (ocrRatio >= nativeRatio + 0.05 && ocrResult.confidence() >= 0.8) {
            return new ParsedPage(p.page().index(), PageMethod.OCR, p.plan().reason()
                    + "; escalated to OCR (%.0f%% function words, confidence %.2f)".formatted(ocrRatio * 100, ocrResult.confidence()),
                    false, ocrResult.confidence(), cost, p.probe(), ocrResult.blocks());
        }
        return new ParsedPage(textLayer.index(), PageMethod.NATIVE, p.plan().reason()
                + "; OCR read no better (%.0f%% function words), kept the text layer".formatted(ocrRatio * 100),
                false, 1.0, cost, p.probe(), textLayer.blocks());
    }

    /** OCR pages read with low confidence are read again at a higher resolution (one call for all of them). */
    private Map<Integer, OcrResult> retryLowConfidence(Document document, List<PagePlan> plans, Map<Integer, OcrResult> read) {
        if (read == null || retryDpi <= ocr.dpi()) {
            return Map.of();
        }
        List<Integer> weak = plans.stream().filter(p -> p.plan().method() == PageMethod.OCR)
                .map(p -> p.page().index())
                .filter(i -> read.get(i) != null && read.get(i).confidence() < lowOcrConfidence && !read.get(i).blocks().isEmpty())
                .toList();
        if (weak.isEmpty()) {
            return Map.of();
        }
        Map<Integer, OcrResult> again = orNull(() -> ocr.parse(document, weak, retryDpi));
        return again == null ? Map.of() : again;
    }

    private ParsedPage parseImage(Document document) {
        Map<Integer, OcrResult> read = orNull(() -> ocr.parse(document, List.of()));
        // an image file has no resolution to raise (the service already upscales small ones): no retry
        return ocrPage(1, "image file", null, read, Map.of());
    }

    private ParsedPage ocrPage(int index, String reason, PageProbe probe, Map<Integer, OcrResult> read,
                               Map<Integer, OcrResult> retried) {
        OcrResult r = read == null ? null : read.get(index);
        if (r == null) {
            return new ParsedPage(index, PageMethod.OCR, reason + "; OCR service unavailable", true, null, 0, probe, List.of());
        }
        int cost = PageMethod.OCR.costUnits();
        OcrResult again = retried.get(index);
        if (again != null) {
            cost += PageMethod.OCR.costUnits();
            boolean better = again.confidence() > r.confidence();
            reason += "; low OCR confidence %.2f, re-read at %d DPI (%.2f)%s".formatted(r.confidence(), retryDpi,
                    again.confidence(), better ? "" : ", kept the first reading");
            if (better) {
                r = again;
            }
        }
        return new ParsedPage(index, PageMethod.OCR, reason, false, r.confidence(), cost, probe, r.blocks());
    }

    /**
     * Images on a text page worth OCR'ing: big enough to hold text, not the whole page (that page is OCR'd anyway),
     * and without a text layer of their own on top (a scan that already carries its OCR text).
     */
    static List<BBox> imageRegions(PdfPage page) {
        double pageArea = Math.max(1, page.width() * page.height());
        List<BBox> out = new ArrayList<>();
        for (BBox img : page.images()) {
            double w = img.x1() - img.x0();
            double h = img.y1() - img.y0();
            double share = w * h / pageArea;
            if (w < 24 || h < 24 || share < 0.015 || share > 0.6) {
                continue;
            }
            int covered = 0;
            for (TextLine l : page.lines()) {
                double cx = (l.bbox().x0() + l.bbox().x1()) / 2;
                double cy = (l.bbox().y0() + l.bbox().y1()) / 2;
                if (cx > img.x0() && cx < img.x1() && cy > img.y0() && cy < img.y1()) {
                    covered += l.text().length();
                }
            }
            if (covered <= 40 && out.stream().noneMatch(o -> o.equals(img))) {
                out.add(img);
            }
            if (out.size() == 6) {
                break;
            }
        }
        return out;
    }

    /** Region OCR costs in proportion to the area it reads (at least 1 unit). */
    private static int regionCost(PagePlan p) {
        double pageArea = Math.max(1, p.page().width() * p.page().height());
        double area = p.regions().stream().mapToDouble(b -> (b.x1() - b.x0()) * (b.y1() - b.y0())).sum();
        return Math.max(1, (int) Math.ceil(PageMethod.OCR.costUnits() * Math.min(1, area / pageArea)));
    }

    private static ParsedPage withReason(ParsedPage p, String reason) {
        return new ParsedPage(p.index(), p.method(), reason, p.pending(), p.confidence(), p.costUnits(), p.probe(), p.blocks());
    }

    /** Null when OCR could not run (service down): those pages are reported, never faked. */
    private <T> T orNull(Supplier<T> call) {
        try {
            return call.get();
        } catch (TierNotConfiguredException e) {
            log.info("OCR skipped: {}", e.getMessage());
            return null;
        }
    }

    static PageProbe probe(PdfPage page) {
        int meaningful = 0;
        StringBuilder text = new StringBuilder();
        for (var line : page.lines()) {
            meaningful += (int) line.text().chars().filter(Character::isLetterOrDigit).count();
            text.append(line.text()).append('\n');
        }
        double garbage = page.glyphs() == 0 ? 0 : (double) page.badGlyphs() / page.glyphs();
        return new PageProbe(page.width(), page.height(), meaningful, page.lines().size(), round(garbage),
                round(page.imageAreaRatio()), page.inkRatio() == null ? null : round(page.inkRatio()),
                TextQuality.score(text.toString()).functionWordRatio());
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static long elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
