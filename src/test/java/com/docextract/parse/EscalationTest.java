package com.docextract.parse;

import com.docextract.document.ContentCache;
import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.parse.model.BBox;
import com.docextract.parse.model.Block;
import com.docextract.parse.model.BlockType;
import com.docextract.parse.model.PageMethod;
import com.docextract.parse.model.ParsedDocument;
import com.docextract.parse.model.ParsedPage;
import com.docextract.parse.pdf.PdfPageReader;
import com.docextract.parse.pdf.TextLine;
import com.docextract.parse.plan.PagePlanner;
import com.docextract.parse.quality.TextQualityTest;
import com.docextract.parse.tier.OcrPageParser;
import com.docextract.parse.tier.OfficeParser;
import com.docextract.strategy.ocr.PythonOcrClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The check-and-escalate loop, with a stand-in OCR whose readings the test controls. */
class EscalationTest {

    /** Records what the engine asked for and answers with prepared readings. */
    static final class StubOcr extends OcrPageParser {
        final Map<Integer, Map<Integer, OcrResult>> byDpi = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        Map<Integer, List<TextLine>> figures = Map.of();

        StubOcr() {
            super(new PythonOcrClient(new ObjectMapper(), "", 1), 300);
        }

        @Override
        public Map<Integer, OcrResult> parse(Document document, List<Integer> pages, int atDpi) {
            calls.add("pages " + pages + " @" + atDpi);
            Map<Integer, OcrResult> out = new HashMap<>();
            for (int p : pages.isEmpty() ? List.of(1) : pages) {
                OcrResult r = byDpi.getOrDefault(atDpi, Map.of()).get(p);
                if (r != null) {
                    out.put(p, r);
                }
            }
            return out;
        }

        @Override
        public Map<Integer, List<TextLine>> figures(Document document, Map<Integer, List<BBox>> regions) {
            calls.add("regions " + regions.keySet());
            return figures;
        }
    }

    static OcrPageParser.OcrResult reading(String text, double confidence) {
        return new OcrPageParser.OcrResult(List.of(new Block(BlockType.PARAGRAPH, text, null, null, null, confidence,
                PageMethod.OCR)), confidence, text);
    }

    private static ParseEngine engine(StubOcr ocr) {
        return new ParseEngine(new DocumentTypeDetector(), new PdfPageReader(new ContentCache(8)),
                new PagePlanner(20, 0.10, 0.60), new OfficeParser(), ocr);
    }

    @Test
    void textLayerThatSpellsNothingIsReplacedByABetterOcrReading() throws Exception {
        StubOcr ocr = new StubOcr();
        ocr.byDpi.put(300, Map.of(1, reading(TextQualityTest.PROSE, 0.97)));
        ParsedDocument d = engine(ocr).parse(new Document("shifted.pdf", null, textPdf(TextQualityTest.shifted(TextQualityTest.PROSE))));

        ParsedPage p = d.pages().getFirst();
        assertThat(p.reason()).startsWith("text layer does not read as language").contains("escalated to OCR");
        assertThat(p.method()).isEqualTo(PageMethod.OCR);
        assertThat(d.markdown()).contains("direct access to development groups");
        assertThat(p.costUnits()).isEqualTo(1 + 20);
    }

    @Test
    void textLayerIsKeptWhenOcrReadsNoBetter() throws Exception {
        String shifted = TextQualityTest.shifted(TextQualityTest.PROSE);
        StubOcr ocr = new StubOcr();
        ocr.byDpi.put(300, Map.of(1, reading(shifted, 0.97))); // the glyphs themselves spell nothing
        ParsedDocument d = engine(ocr).parse(new Document("table.pdf", null, textPdf(shifted)));

        ParsedPage p = d.pages().getFirst();
        assertThat(p.method()).isEqualTo(PageMethod.NATIVE);
        assertThat(p.reason()).contains("kept the text layer");
    }

    @Test
    void lowConfidenceOcrIsReadAgainAtHigherResolution() throws Exception {
        StubOcr ocr = new StubOcr();
        ocr.byDpi.put(300, Map.of(2, reading("blurry", 0.62)));
        ocr.byDpi.put(450, Map.of(2, reading("sharp", 0.93)));
        ParsedDocument d = engine(ocr).parse(new Document("mixed.pdf", null, ParseEngineTest.mixedPdf()));

        ParsedPage scan = d.pages().get(1);
        assertThat(ocr.calls).contains("pages [2] @300", "pages [2] @450");
        assertThat(scan.reason()).contains("re-read at 450 DPI");
        assertThat(scan.confidence()).isEqualTo(0.93);
        assertThat(scan.blocks().getFirst().text()).isEqualTo("sharp");
        assertThat(scan.costUnits()).isEqualTo(40);
        assertThat(d.pages().getFirst().method()).isEqualTo(PageMethod.NATIVE); // the text page is never OCR'd
    }

    @Test
    void confidentOcrIsNotReadTwice() throws Exception {
        StubOcr ocr = new StubOcr();
        ocr.byDpi.put(300, Map.of(2, reading("clear", 0.97)));
        engine(ocr).parse(new Document("mixed.pdf", null, ParseEngineTest.mixedPdf()));
        assertThat(ocr.calls).containsExactly("pages [2] @300");
    }

    @Test
    void textInAnImageOnATextPageBecomesAFigureInReadingOrder() throws Exception {
        StubOcr ocr = new StubOcr();
        ocr.figures = Map.of(1, List.of(TextLine.figure("Multi-Head Attention\nFeed Forward", new BBox(72, 300, 372, 500), 0.95)));
        ParsedDocument d = engine(ocr).parse(new Document("figure.pdf", null, pdfWithFigure()));

        ParsedPage p = d.pages().getFirst();
        assertThat(ocr.calls).containsExactly("regions [1]");
        assertThat(p.method()).isEqualTo(PageMethod.NATIVE);
        assertThat(p.reason()).contains("OCR'd 1 image region(s), 1 with text");
        List<BlockType> types = p.blocks().stream().map(Block::type).toList();
        assertThat(types).containsExactly(BlockType.PARAGRAPH, BlockType.FIGURE, BlockType.PARAGRAPH);
        assertThat(d.markdown()).contains("> **[Figure text]** Multi-Head Attention · Feed Forward");
        assertThat(p.costUnits()).isGreaterThan(1).isLessThan(20);
    }

    @Test
    void figuresCanBeSwitchedOff() throws Exception {
        StubOcr ocr = new StubOcr();
        engine(ocr).parse(new Document("figure.pdf", null, pdfWithFigure()), new ParseEngine.Options(false));
        assertThat(ocr.calls).isEmpty();
    }

    /** One page of 11pt text, wrapped at ~90 characters. */
    static byte[] textPdf(String text) throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                float y = 760;
                for (String line : text.split("(?<=\\G.{80,}?)\\s")) {
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.newLineAtOffset(72, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 14;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    /** Text above and below a 300x200 pt image. */
    static byte[] pdfWithFigure() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            BufferedImage img = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                for (Object[] t : new Object[][] {{760f, "The architecture is shown below and explained in the text."},
                        {250f, "As the figure shows, attention layers alternate with feed-forward layers."}}) {
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.newLineAtOffset(72, (float) t[0]);
                    cs.showText((String) t[1]);
                    cs.endText();
                }
                // top-left frame: x 72..372, y 342..542
                cs.drawImage(LosslessFactory.createFromImage(pdf, img), 72, 842 - 542, 300, 200);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }
}
