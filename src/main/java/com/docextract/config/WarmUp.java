package com.docextract.config;

import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.schema.SchemaCatalog;
import com.docextract.strategy.NativeTextStrategy;
import com.docextract.strategy.text.TextExtractor;
import com.docextract.validation.ValidationEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs one throw-away extraction when the app starts, so the first real request does not pay for one-time setup
 * (PDFBox font cache, Tika parsers, class loading, JIT of the mapper and validator): without it the first batch
 * after a restart took ~5 s even for text PDFs that normally take ~0.1 s.
 */
@Component
public class WarmUp {

    private static final Logger log = LoggerFactory.getLogger(WarmUp.class);

    private final NativeTextStrategy nativeText;
    private final TextExtractor textExtractor;
    private final DocumentTypeDetector detector;
    private final ValidationEngine validation;
    private final SchemaCatalog catalog;
    private final ObjectMapper json;

    public WarmUp(NativeTextStrategy nativeText, TextExtractor textExtractor, DocumentTypeDetector detector,
                  ValidationEngine validation, SchemaCatalog catalog, ObjectMapper json) {
        this.nativeText = nativeText;
        this.textExtractor = textExtractor;
        this.detector = detector;
        this.validation = validation;
        this.catalog = catalog;
        this.json = json;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        long start = System.nanoTime();
        try {
            Document pdf = new Document("warm-up.pdf", "application/pdf", samplePdf());
            Document txt = new Document("warm-up.txt", "text/plain",
                    "Invoice No: W-1\nDate: 2024-01-01\nTotal: 10.00\n".getBytes(StandardCharsets.UTF_8));
            for (Document d : List.of(pdf, txt)) {
                detector.detect(d);
                for (var preset : catalog.all()) {
                    var result = nativeText.extract(d, preset.schema());
                    validation.validateExtraction(json.valueToTree(result.fields()), preset.schema(), List.of());
                }
            }
            textExtractor.hasTextLayer(pdf);
            log.info("warm-up done in {} ms", (System.nanoTime() - start) / 1_000_000);
        } catch (Exception e) {
            log.warn("warm-up skipped: {}", e.toString());
        }
    }

    private static byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(72, 720);
                cs.showText("INVOICE   Invoice No: W-1   Date: 2024-01-01   Total: 10.00");
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
