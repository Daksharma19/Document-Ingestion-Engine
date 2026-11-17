package com.docextract.strategy.text;

import com.docextract.document.Document;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    @Test
    void extractsTextFromPdfWithPdfBox() throws Exception {
        byte[] pdf = pdfWithText("Invoice INV-001 Total: 42.00");
        String text = extractor.extractText(new Document("x.pdf", "application/pdf", pdf));
        assertThat(text).contains("INV-001").contains("Total: 42.00");
    }

    @Test
    void detectsPdfByMagicBytesEvenWithoutMetadata() throws Exception {
        byte[] pdf = pdfWithText("hello");
        assertThat(new Document("blob", null, pdf).isPdf()).isTrue();
    }

    @Test
    void extractsTextFromHtmlWithTika() {
        byte[] html = "<html><body><p>Total: 99.50</p></body></html>".getBytes(StandardCharsets.UTF_8);
        String text = extractor.extractText(new Document("x.html", "text/html", html));
        assertThat(text).contains("Total: 99.50");
    }

    static byte[] pdfWithText(String line) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(line);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
