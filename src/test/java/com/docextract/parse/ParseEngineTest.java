package com.docextract.parse;

import com.docextract.document.ContentCache;
import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.parse.model.Block;
import com.docextract.parse.model.BlockType;
import com.docextract.parse.model.PageMethod;
import com.docextract.parse.model.ParsedDocument;
import com.docextract.parse.model.ParsedPage;
import com.docextract.parse.pdf.PdfPageReader;
import com.docextract.parse.plan.PagePlanner;
import com.docextract.parse.tier.OfficeParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParseEngineTest {

    private final ParseEngine engine = new ParseEngine(new DocumentTypeDetector(),
            new PdfPageReader(new ContentCache(16)), new PagePlanner(20, 0.10, 0.60), new OfficeParser(),
            new com.docextract.parse.tier.OcrPageParser(new com.docextract.strategy.ocr.PythonOcrClient(new com.fasterxml.jackson.databind.ObjectMapper(), "", 5), 300));

    @Test
    void mixedPdfRoutesEachPageOnItsOwn() throws Exception {
        ParsedDocument doc = engine.parse(new Document("mixed.pdf", "application/pdf", mixedPdf()));

        assertThat(doc.status()).isEqualTo("OK");
        assertThat(doc.pages()).hasSize(2);
        ParsedPage text = doc.pages().get(0);
        ParsedPage scan = doc.pages().get(1);
        assertThat(text.method()).isEqualTo(PageMethod.NATIVE);
        assertThat(text.pending()).isFalse();
        assertThat(scan.method()).isEqualTo(PageMethod.OCR);
        assertThat(scan.reason()).contains("no text layer");
        assertThat(scan.probe().imageAreaRatio()).isGreaterThan(0.9);
        // only the text page is paid for; the OCR page is pending until the OCR tier is wired in
        assertThat(doc.costUnits()).isEqualTo(1);

        List<Block> blocks = text.blocks();
        assertThat(blocks.getFirst().type()).isEqualTo(BlockType.HEADING);
        assertThat(blocks.getFirst().text()).isEqualTo("Quarterly Report");
        assertThat(blocks.getFirst().level()).isEqualTo(1);
        assertThat(blocks).anyMatch(b -> b.type() == BlockType.PARAGRAPH
                && b.text().equals("Revenue grew in every region this quarter, led by strong demand for the new product line."));
        assertThat(blocks.stream().filter(b -> b.type() == BlockType.LIST_ITEM).map(Block::text))
                .containsExactly("North up 12%", "South up 8%");
        Block table = blocks.stream().filter(b -> b.type() == BlockType.TABLE).findFirst().orElseThrow();
        assertThat(table.rows()).containsExactly(
                List.of("Region", "Q1", "Q2"), List.of("North", "100", "112"), List.of("South", "90", "97"));

        assertThat(doc.markdown()).contains("# Quarterly Report", "- North up 12%", "| Region | Q1 | Q2 |",
                "| --- | --- | --- |", "<!-- page 2 -->");
    }

    @Test
    void docxKeepsItsOwnStructure() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("corpus/receipt-002.docx"));
        ParsedDocument doc = engine.parse(new Document("receipt-002.docx", null, bytes));

        assertThat(doc.status()).isEqualTo("OK");
        assertThat(doc.kind()).isEqualTo("OFFICE_OR_TEXT");
        assertThat(doc.pages()).singleElement().satisfies(p -> {
            assertThat(p.method()).isEqualTo(PageMethod.NATIVE);
            assertThat(p.blocks()).isNotEmpty();
        });
        assertThat(doc.markdown()).isNotBlank();
    }

    @Test
    void scannedPdfIsPlannedForOcr() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("corpus/scanned-004.pdf"));
        ParsedDocument doc = engine.parse(new Document("scanned-004.pdf", null, bytes));

        assertThat(doc.pages()).allSatisfy(p -> {
            assertThat(p.method()).isEqualTo(PageMethod.OCR);
            assertThat(p.pending()).isTrue();
        });
    }

    @Test
    void textPdfFromCorpusIsReadNatively() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of("corpus/invoice-001.pdf"));
        ParsedDocument doc = engine.parse(new Document("invoice-001.pdf", null, bytes));

        assertThat(doc.pages()).allSatisfy(p -> assertThat(p.method()).isEqualTo(PageMethod.NATIVE));
        assertThat(doc.markdown()).contains("INV-2024-001");
    }

    @Test
    void garbledTextLayerIsSentToOcr() {
        PagePlanner planner = new PagePlanner(20, 0.10, 0.60);
        var plan = planner.plan(new com.docextract.parse.model.PageProbe(612, 792, 500, 30, 0.4, 0, null, null));
        assertThat(plan.method()).isEqualTo(PageMethod.OCR);
        assertThat(plan.reason()).contains("garbled");
    }

    /** Page 1: heading, paragraph, bullets, a 3x3 table. Page 2: a full-page image with no text (a scan). */
    static byte[] mixedPdf() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                text(cs, bold, 22, 72, 770, "Quarterly Report");
                text(cs, regular, 11, 72, 730, "Revenue grew in every region this quarter, led by strong");
                text(cs, regular, 11, 72, 716, "demand for the new product line.");
                text(cs, regular, 11, 72, 680, "- North up 12%");
                text(cs, regular, 11, 72, 666, "- South up 8%");
                float[] cols = {72, 250, 400};
                String[][] rows = {{"Region", "Q1", "Q2"}, {"North", "100", "112"}, {"South", "90", "97"}};
                for (int r = 0; r < rows.length; r++) {
                    for (int c = 0; c < 3; c++) {
                        text(cs, regular, 11, cols[c], 620 - r * 16, rows[r][c]);
                    }
                }
            }
            PDPage scan = new PDPage(PDRectangle.A4);
            pdf.addPage(scan);
            BufferedImage img = new BufferedImage(200, 280, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 200, 280);
            g.setColor(Color.BLACK);
            g.drawString("scanned text", 20, 40);
            g.dispose();
            PDImageXObject x = LosslessFactory.createFromImage(pdf, img);
            try (PDPageContentStream cs = new PDPageContentStream(pdf, scan)) {
                cs.drawImage(x, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }
}
