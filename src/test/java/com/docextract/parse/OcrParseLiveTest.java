package com.docextract.parse;

import com.docextract.document.ContentCache;
import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.parse.model.PageMethod;
import com.docextract.parse.model.ParsedDocument;
import com.docextract.parse.pdf.PdfPageReader;
import com.docextract.parse.plan.PagePlanner;
import com.docextract.parse.tier.OcrPageParser;
import com.docextract.parse.tier.OfficeParser;
import com.docextract.strategy.ocr.PythonOcrClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against the real Python OCR service (ocr-service/run.sh); skipped when it is not running. */
class OcrParseLiveTest {

    private PythonOcrClient client;
    private ParseEngine engine;

    @BeforeEach
    void setUp() {
        client = new PythonOcrClient(new ObjectMapper(), "http://127.0.0.1:8001", 180);
        assumeTrue(client.isAvailable(), "OCR service not running on :8001");
        engine = new ParseEngine(new DocumentTypeDetector(), new PdfPageReader(new ContentCache(16)),
                new PagePlanner(20, 0.10, 0.60), new OfficeParser(), new OcrPageParser(client, 300));
    }

    @Test
    void scannedPdfIsReadByOcr() throws Exception {
        ParsedDocument doc = engine.parse(new Document("scanned-004.pdf", null,
                Files.readAllBytes(Path.of("corpus/scanned-004.pdf"))));

        assertThat(doc.pages()).allSatisfy(p -> {
            assertThat(p.method()).isEqualTo(PageMethod.OCR);
            assertThat(p.pending()).isFalse();
            assertThat(p.confidence()).isGreaterThan(0.8);
        });
        assertThat(doc.markdown()).contains("NORTHWIND LOGISTICS", "NW-55012", "281.05");
        assertThat(doc.costUnits()).isEqualTo(20 * doc.pages().size());
    }

    @Test
    void imageFileIsReadByOcr() throws Exception {
        ParsedDocument doc = engine.parse(new Document("scanned-003.png", null,
                Files.readAllBytes(Path.of("corpus/scanned-003.png"))));

        assertThat(doc.pages()).singleElement().satisfies(p -> assertThat(p.method()).isEqualTo(PageMethod.OCR));
        assertThat(doc.markdown()).contains("BRIGHT ELECTRICAL", "BE-7781", "312.50");
    }

    @Test
    void mixedPdfOcrsOnlyTheScannedPage() throws Exception {
        ParsedDocument doc = engine.parse(new Document("mixed.pdf", "application/pdf", ParseEngineTest.mixedPdf()));

        assertThat(doc.pages().get(0).method()).isEqualTo(PageMethod.NATIVE);
        assertThat(doc.pages().get(1).method()).isEqualTo(PageMethod.OCR);
        assertThat(doc.pages().get(1).pending()).isFalse();
        assertThat(doc.pages().get(1).blocks()).anySatisfy(b -> assertThat(b.text()).containsIgnoringCase("scanned"));
        assertThat(doc.costUnits()).isEqualTo(1 + 20);
    }
}
