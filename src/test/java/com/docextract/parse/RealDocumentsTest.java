package com.docextract.parse;

import com.docextract.document.ContentCache;
import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.parse.model.BlockType;
import com.docextract.parse.model.PageMethod;
import com.docextract.parse.model.ParsedDocument;
import com.docextract.parse.model.ParsedPage;
import com.docextract.parse.pdf.PdfPageReader;
import com.docextract.parse.plan.PagePlanner;
import com.docextract.parse.tier.OcrPageParser;
import com.docextract.parse.tier.OfficeParser;
import com.docextract.strategy.ocr.PythonOcrClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression tests on real public documents (an arXiv paper, the IRS W-9, an IBM Redbook, a Swiss newspaper,
 * docling's own test files, Tesseract's eurotext scan). They are not committed (not ours to redistribute): download
 * them into {@code test-docs/real/} (see docs/REAL_DOCUMENTS.md). Missing files and, for OCR cases, a stopped OCR
 * service skip the test instead of failing it.
 */
class RealDocumentsTest {

    static final Path DIR = Path.of("test-docs/real");

    private final PythonOcrClient ocrClient = new PythonOcrClient(new ObjectMapper(), "http://127.0.0.1:8001", 180);
    private final ParseEngine engine = new ParseEngine(new DocumentTypeDetector(),
            new PdfPageReader(new ContentCache(16)), new PagePlanner(20, 0.10, 0.60), new OfficeParser(),
            new OcrPageParser(ocrClient, 300));

    private ParsedDocument parse(String name) throws Exception {
        Path p = DIR.resolve(name);
        assumeTrue(Files.exists(p), "real document not downloaded: " + p);
        ParsedDocument d = engine.parse(new Document(name, null, Files.readAllBytes(p)));
        assertThat(d.status()).as(d.error()).isEqualTo("OK");
        return d;
    }

    private void requireOcr() {
        assumeTrue(ocrClient.isAvailable(), "OCR service not running on :8001");
    }

    @Test
    void newspaperColumnsReadInOrderWithRealSpaces() throws Exception {
        ParsedDocument d = parse("newspaper-00.pdf");
        assertThat(d.pages()).allSatisfy(p -> assertThat(p.method()).isEqualTo(PageMethod.NATIVE));
        // six narrow columns: reading across the page interleaved them, and tight type lost word spaces
        assertThat(d.markdown())
                .contains("Heute lest ihr die letzte Ausgabe der Pendlerzeitung 20 Minuten.")
                .contains("Der Dank gilt ebenso unseren Werbekunden")
                .doesNotContain("derWerbemarkt");
    }

    @Test
    void researchPaperIsReadFromItsTextLayerOnly() throws Exception {
        ParsedDocument d = parse("attention-1706.03762.pdf");
        assertThat(d.pages()).hasSize(15).allSatisfy(p -> assertThat(p.method()).isEqualTo(PageMethod.NATIVE));
        // every page from its text layer: one unit each when figure OCR is off
        assertThat(engine.parse(new Document("a.pdf", null, Files.readAllBytes(DIR.resolve("attention-1706.03762.pdf"))),
                new ParseEngine.Options(false)).costUnits()).isEqualTo(15);
        assertThat(d.markdown()).contains("# Attention Is All You Need",
                "The dominant sequence transduction models are based on complex recurrent");
        // rotated words in the attention figures stay whole (PDFBox breaks them: "th" | "is")
        assertThat(d.pages().get(12).blocks().stream().map(b -> b.text()).toList()).noneMatch(t -> t.contains("th is"));
    }

    @Test
    void taxFormIsNative() throws Exception {
        ParsedDocument d = parse("irs-fw9.pdf");
        assertThat(d.pages()).hasSize(6).allSatisfy(p -> assertThat(p.method()).isEqualTo(PageMethod.NATIVE));
        assertThat(d.markdown()).contains("Request for Taxpayer Identification Number and Certification");
    }

    @Test
    void redbookSendsOnlyTheGarbledPageToOcrAndSkipsTheBlankOne() throws Exception {
        requireOcr();
        ParsedDocument d = parse("redp5110_sampled.pdf");
        ParsedPage garbled = d.pages().get(2);
        ParsedPage blank = d.pages().get(16);
        assertThat(garbled.method()).isEqualTo(PageMethod.OCR);
        assertThat(garbled.reason()).contains("garbled");
        // its text layer is letter-shifted ("(QKDQFH" for "Enhance"); OCR reads the page itself
        assertThat(garbled.blocks()).anySatisfy(b -> assertThat(b.text()).contains("Enhance the performance of your database"));
        assertThat(blank.reason()).isEqualTo("blank page");
        assertThat(d.pages().stream().filter(p -> p.method() == PageMethod.OCR)).hasSize(1);
        // 16 text pages + 1 OCR page + figure regions: far below OCR'ing all 18 pages (360)
        assertThat(d.costUnits()).isLessThan(100);
        // its text layer would cost 17 + 20 without figure OCR
        assertThat(engine.parse(new Document("redp5110_sampled.pdf", null, Files.readAllBytes(DIR.resolve("redp5110_sampled.pdf"))),
                new ParseEngine.Options(false)).costUnits()).isEqualTo(17 + 20);
    }

    @Test
    void textInsideFiguresIsRecovered() throws Exception {
        requireOcr();
        ParsedDocument paper = parse("attention-1706.03762.pdf");
        // the Transformer diagram (page 3) is a PNG: its labels exist in no text layer
        assertThat(paper.pages().get(2).blocks()).anySatisfy(b -> {
            assertThat(b.type()).isEqualTo(BlockType.FIGURE);
            assertThat(b.text()).contains("Multi-Head", "Positional", "Softmax");
        });
        assertThat(paper.pages().get(2).method()).isEqualTo(PageMethod.NATIVE);
        assertThat(paper.costUnits()).isLessThan(15 * 2);

        ParsedDocument chart = parse("picture_classification.pdf");
        assertThat(chart.markdown()).contains("[Figure text]").contains("woodlot");
    }

    @Test
    void vectorOnlyTablePageNeedsNoOcr() throws Exception {
        ParsedDocument d = parse("table_mislabeled_as_picture.pdf");
        assertThat(d.costUnits()).isEqualTo(1);
        assertThat(d.markdown()).contains("university-based student law clinics");
    }

    @Test
    void lowResolutionScanIsReadWithoutMisreads() throws Exception {
        requireOcr();
        ParsedDocument d = parse("2206.01062.tif");
        assertThat(d.pages()).singleElement().satisfies(p -> {
            assertThat(p.method()).isEqualTo(PageMethod.OCR);
            assertThat(p.confidence()).isGreaterThan(0.95);
        });
        assertThat(d.markdown())
                .contains("DocLayNet: A Large Human-Annotated Dataset for Document-Layout Analysis",
                        "high-quality PDF document conversion".replace("high-", "high"), "public, large ground-truth datasets")
                .doesNotContain("a 00[.]", "converion", "pubhc");
    }

    @Test
    void eurotextScan() throws Exception {
        requireOcr();
        assertThat(parse("eurotext.png").markdown())
                .contains("The (quick) [brown] {fox} jumps! Over the $43,456.78 <lazy> #90 dog & duck/goose")
                .contains("Le renard brun «rapide» saute par-dessus le chien paresseux.");
    }

    @Test
    void wordDocumentKeepsListsAndTables() throws Exception {
        ParsedDocument d = parse("word_sample.docx");
        var blocks = d.pages().getFirst().blocks();
        assertThat(blocks.stream().filter(b -> b.type() == BlockType.LIST_ITEM)).hasSizeGreaterThanOrEqualTo(6);
        assertThat(blocks).anySatisfy(b -> assertThat(b.text()).isEqualTo("Paddle about"));
        assertThat(blocks.stream().filter(b -> b.type() == BlockType.TABLE).findFirst().orElseThrow().rows())
                .contains(java.util.List.of("Berries", "Blueberry, Strawberry, Cranberry", "150"));
        assertThat(d.markdown()).doesNotContain("image1.png");
    }

    @Test
    void spreadsheetSheetsBecomeTables() throws Exception {
        ParsedDocument d = parse("xlsx_01.xlsx");
        assertThat(d.markdown()).contains("# Sheet1", "| first | second | third |", "| 6 | 0 | -6 |");
    }
}
