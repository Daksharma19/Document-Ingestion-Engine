package com.docextract.corpus;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Regenerates the sample documents in corpus/. The .labels.json files are hand-written ground truth
 * and are not touched. Run: ./mvnw test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java
 *   -Dexec.classpathScope=test -Dexec.mainClass=com.docextract.corpus.SampleCorpusGenerator
 */
public final class SampleCorpusGenerator {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "corpus");
        Files.createDirectories(dir);

        pdf(dir.resolve("invoice-001.pdf"), List.of(
                "ACME Supplies Ltd",
                "Vendor: ACME Supplies Ltd",
                "Invoice No: INV-2024-001",
                "Invoice Date: 2024-03-12",
                "",
                "Description                 Amount",
                "Printer paper (10 reams)     45.00",
                "Toner cartridge              80.00",
                "Delivery                     15.00",
                "",
                "Subtotal: 140.00",
                "Tax: 25.20",
                "Total: $165.20"));

        docx(dir.resolve("receipt-002.docx"), List.of(
                "Corner Coffee House",
                "12 High Street, Bristol",
                "05/01/2024  09:42",
                "Flat white        3.20",
                "Croissant         2.75",
                "Orange juice     17.52",
                "Total Items: 3",
                "Grand Total: £23.47",
                "Thank you for visiting!"));

        png(dir.resolve("scanned-003.png"), List.of(
                "BRIGHT ELECTRICAL",
                "Invoice No: BE-7781",
                "Invoice Date: 2023-11-30",
                "Total: 312.50"));
    }

    private static void pdf(Path out, List<String> lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.COURIER), 11);
                cs.setLeading(15);
                cs.newLineAtOffset(60, 720);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out.toFile());
        }
    }

    private static void docx(Path out, List<String> lines) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); OutputStream os = Files.newOutputStream(out)) {
            for (String line : lines) {
                doc.createParagraph().createRun().setText(line);
            }
            doc.write(os);
        }
    }

    // A rasterised document with no text layer, standing in for a scan.
    private static void png(Path out, List<String> lines) throws Exception {
        BufferedImage img = new BufferedImage(600, 60 + lines.size() * 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 22));
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(lines.get(i), 30, 50 + i * 40);
        }
        g.dispose();
        ImageIO.write(img, "png", out.toFile());
    }
}
