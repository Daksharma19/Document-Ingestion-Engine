package com.docextract.parse;

import com.docextract.document.ContentCache;
import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.parse.layout.ReadingOrder;
import com.docextract.parse.model.BBox;
import com.docextract.parse.model.ParsedDocument;
import com.docextract.parse.pdf.PdfPageReader;
import com.docextract.parse.pdf.TextLine;
import com.docextract.parse.plan.PagePlanner;
import com.docextract.parse.tier.OcrPageParser;
import com.docextract.parse.tier.OfficeParser;
import com.docextract.strategy.ocr.PythonOcrClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regressions found by the parser benchmark (bench/): glyphs drawn out of order, a two-line table cell split off its
 * row, and headings set in TeX bold or italic. Uses the documents in test-docs/; a missing file skips the test.
 */
class LayoutFixesTest {

    private final ParseEngine engine = new ParseEngine(new DocumentTypeDetector(),
            new PdfPageReader(new ContentCache(16)), new PagePlanner(20, 0.10, 0.60), new OfficeParser(),
            new OcrPageParser(new PythonOcrClient(new ObjectMapper(), "http://127.0.0.1:1", 1), 300));

    private String markdown(String path) throws Exception {
        Path p = Path.of("test-docs", path);
        assumeTrue(Files.exists(p), "test document missing: " + p);
        ParsedDocument d = engine.parse(new Document(p.getFileName().toString(), null, Files.readAllBytes(p)),
                new ParseEngine.Options(false));
        assertThat(d.status()).as(d.error()).isEqualTo("OK");
        return d.markdown();
    }

    @Test
    void glyphsDrawnOutOfOrderAreReadInPositionOrder() throws Exception {
        // the generator draws "(", "%", ")" first and fills the letters in afterwards
        String md = markdown("invoice12904507353526811818.pdf");
        assertThat(md).contains("CGST(2.50%) 0.53", "SGST(2.50%) 0.83", "Mithai Shop/Show Room(Tax-Invoice)")
                .doesNotContain("( %)");
    }

    @Test
    void twoLineTableCellStaysOnItsRow() throws Exception {
        String md = markdown("wordpress-pdf-invoice-plugin-sample.pdf");
        assertThat(md.lines().filter(l -> l.contains("Web Design")).findFirst().orElse(""))
                .contains("1.00", "This is a sample description", "$85.00", "0.00%");
    }

    @Test
    void texBoldSectionTitlesAreHeadings() throws Exception {
        assertThat(markdown("real/2305.03393v1-pg9.pdf"))
                .containsPattern("(?m)^#+ 5\\.1 Hyper Parameter Optimization$")
                .containsPattern("(?m)^#+ 5\\.2 Quantitative Results$");
    }

    @Test
    void italicRunInSubheadsAreHeadings() throws Exception {
        assertThat(markdown("real/amt_handbook_sample.pdf"))
                .containsPattern("(?m)^#+ Boots Self-Locking Nut$")
                .containsPattern("(?m)^#+ Stainless Steel Self-Locking Nut$");
    }

    @Test
    void figureOverhangingTheGutterDoesNotMergeColumns() throws Exception {
        Path p = Path.of("test-docs", "real/amt_handbook_sample.pdf");
        assumeTrue(Files.exists(p), "test document missing: " + p);
        List<TextLine> segs = new ArrayList<>(new PdfPageReader(new ContentCache(4))
                .read(new Document(p.getFileName().toString(), null, Files.readAllBytes(p))).getFirst().lines());
        // OCR'd image region as the service returns it: the picture box runs past the 311-321pt gutter
        segs.add(TextLine.figure("Elastic anchor nut", new BBox(203, 542, 341, 606), 1.0));
        List<String> text = ReadingOrder.order(segs).stream().map(TextLine::text).toList();
        assertThat(text).contains("to the supporting structure by the nut and bolt. Plates must", "Elastic Stop Nut");
        assertThat(text.indexOf("Elastic Stop Nut"))
                .isGreaterThan(text.indexOf("insert to be compressed when the nut is tightened."));
    }
}
