package com.docextract.strategy.ocr;

import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.strategy.ExtractionResult;
import com.docextract.strategy.OcrStrategy;
import com.docextract.strategy.TierNotConfiguredException;
import com.docextract.strategy.UnsupportedDocumentException;
import com.docextract.strategy.mapping.KeywordRegexFieldMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs real Tesseract (via Tess4J's bundled native library) on the checked-in scanned samples. */
class OcrStrategyTest {

    private static final String TESSDATA = System.getenv().getOrDefault("TESSDATA_PREFIX", ".tools/tessdata");
    private final ObjectMapper json = new ObjectMapper();
    private OcrStrategy ocr;

    @BeforeEach
    void setUp() {
        OcrEngine engine = new OcrEngine(TESSDATA, "eng");
        assumeTrue(engine.notConfiguredReason().isEmpty(), "eng.traineddata not installed; see README");
        ocr = new OcrStrategy(engine, new KeywordRegexFieldMapper(), new DocumentTypeDetector(), 10);
    }

    private ExtractionResult run(String file) throws Exception {
        JsonNode labels = json.readTree(Path.of("corpus", file.replaceFirst("\\.[a-z]+$", ".labels.json")).toFile());
        return ocr.extract(new Document(file, null, Files.readAllBytes(Path.of("corpus", file))), labels.get("schema"));
    }

    @Test
    void readsAnImageOnlyPdfWithSpeckleNoise() throws Exception {
        ExtractionResult r = run("scanned-004.pdf");
        assertThat(r.rawText()).contains("NORTHWIND LOGISTICS").contains("Tax: 25.55");
        assertThat(r.fields())
                .containsEntry("vendor", "NORTHWIND LOGISTICS")
                .containsEntry("invoiceNumber", "NW-55012")
                .containsEntry("invoiceDate", "2024-02-14")
                .containsEntry("tax", new BigDecimal("25.55"))
                .containsEntry("total", new BigDecimal("281.05"));
        assertThat(r.costUnits()).isEqualTo(20);
    }

    @Test
    void readsAPngScan() throws Exception {
        ExtractionResult r = run("scanned-003.png");
        assertThat(r.fields()).containsEntry("invoiceNumber", "BE-7781").containsEntry("total", new BigDecimal("312.50"));
    }

    @Test
    void refusesFormatsWithoutAPageImage() throws Exception {
        assertThatThrownBy(() -> run("receipt-002.docx")).isInstanceOf(UnsupportedDocumentException.class);
    }

    @Test
    void missingLanguageDataIsNotConfiguredRatherThanACrash() {
        OcrStrategy unconfigured = new OcrStrategy(new OcrEngine("does-not-exist", "eng"),
                new KeywordRegexFieldMapper(), new DocumentTypeDetector(), 10);
        assertThat(unconfigured.notConfiguredReason()).isPresent();
        assertThatThrownBy(() -> unconfigured.extract(new Document("a.png", null, new byte[]{1}), json.createObjectNode()))
                .isInstanceOf(TierNotConfiguredException.class);
    }

    @Test
    void preprocessingRemovesIsolatedSpecklesAndBinarises() {
        int[][] g = new int[5][5];
        for (int[] row : g) {
            java.util.Arrays.fill(row, 240);
        }
        g[2][2] = 0; // one dark speck on a light page
        int[][] filtered = ImagePreprocessor.median3x3(g);
        assertThat(filtered[2][2]).isEqualTo(240);
        assertThat(ImagePreprocessor.otsuThreshold(new int[][]{{10, 10, 250, 250}})).isBetween(10, 249);
    }
}
