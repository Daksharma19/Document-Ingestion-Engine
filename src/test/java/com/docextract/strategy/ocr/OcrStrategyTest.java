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
    void noiseDetectorSeparatesSpeckledScansFromCleanPages() throws Exception {
        var detector = new DocumentTypeDetector();
        java.util.function.Function<String, java.awt.image.BufferedImage> page = f -> {
            try {
                var doc = new Document(f, null, Files.readAllBytes(Path.of("corpus", f)));
                return com.docextract.strategy.text.PageImages.render(doc, detector.detect(doc).kind(), 300, 1).getFirst();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertThat(ImagePreprocessor.isNoisy(page.apply("scanned-004.pdf"))).isTrue();
        assertThat(ImagePreprocessor.isNoisy(page.apply("invoice-001.pdf"))).isFalse();
        assertThat(ImagePreprocessor.isNoisy(page.apply("scanned-003.png"))).isFalse();
    }

    @Test
    void layoutCleanInvertsDarkBarsAndErasesRuledLines() {
        var img = new java.awt.image.BufferedImage(1000, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 1000, 400);
        g.setColor(new java.awt.Color(40, 40, 40));
        g.fillRect(50, 50, 900, 60);          // a dark header bar
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(100, 70, 20, 20);          // white "text" inside the bar
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(50, 300, 900, 3);          // a table rule
        g.dispose();
        var out = ImagePreprocessor.layoutClean(img).getRaster();
        assertThat(out.getSample(500, 60, 0)).as("bar background becomes light").isGreaterThan(200);
        assertThat(out.getSample(110, 80, 0)).as("its white caption becomes dark").isLessThan(60);
        assertThat(out.getSample(500, 301, 0)).as("ruled line erased").isEqualTo(255);
    }

    @Test
    void ruleArtifactsAndSplitSlashesAreRemovedFromOcrText() {
        assertThat(OcrEngine.removeRuleArtifacts("|   TOTAL   |   $313.90   |\nJavaScript /TypeScript and a | b"))
                .isEqualTo("TOTAL       $313.90\nJavaScript/TypeScript and a   b\n");
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
