package com.docextract.strategy.text;

import com.docextract.document.ContentCache;
import com.docextract.document.Document;
import com.docextract.strategy.ExtractionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * PDFs go through PDFBox directly; every other format goes through Tika's auto-detection. A PDF is parsed once:
 * the text-layer check (routing) and the extraction share the result, which is also cached by content.
 */
@Component
public class TextExtractor {

    /** Fewer letters/digits than this per page means the PDF has no usable text layer (likely a scan). */
    static final int MIN_CHARS_PER_PAGE = 20;

    private final Tika tika;
    private final ContentCache cache;

    public TextExtractor() {
        this(new ContentCache(0));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TextExtractor(ContentCache cache) {
        this.tika = new Tika();
        this.tika.setMaxStringLength(10_000_000);
        this.cache = cache;
    }

    /** A PDF's text layer and page count, from one parse. */
    private record PdfText(String text, int pages) {
    }

    public String extractText(Document document) {
        if (document.isPdf()) {
            return pdfText(document).text();
        }
        return cache.get(document, "text:tika", () -> {
            try {
                return tika.parseToString(new ByteArrayInputStream(document.bytes()));
            } catch (Exception e) {
                throw new ExtractionException("could not extract text from " + document.filename(), e);
            }
        });
    }

    /** True when the PDF's embedded text layer carries real content on average across its pages. */
    public boolean hasTextLayer(Document document) {
        PdfText pdf = pdfText(document);
        long meaningful = pdf.text().chars().filter(Character::isLetterOrDigit).count();
        return meaningful >= (long) MIN_CHARS_PER_PAGE * Math.max(1, pdf.pages());
    }

    private PdfText pdfText(Document document) {
        return cache.get(document, "text:pdf", () -> {
            try (PDDocument pdf = Loader.loadPDF(document.bytes())) {
                PDFTextStripper stripper = new ColumnGapStripper();
                stripper.setSortByPosition(true);
                return new PdfText(stripper.getText(pdf), pdf.getNumberOfPages());
            } catch (Exception e) {
                throw new ExtractionException("could not read PDF " + document.filename(), e);
            }
        });
    }

    /**
     * PDFBox joins everything on one baseline with single spaces, so two side-by-side columns
     * ("INVOICE # 54167" | "PHONE NUMBER 916-555-0123") read as one sentence. This stripper writes a wide
     * horizontal gap (more than ~2 character widths) as three spaces, which downstream code treats as a column break.
     */
    static final class ColumnGapStripper extends PDFTextStripper {
        static final String COLUMN_GAP = "   ";
        private float lastEndX = Float.NaN;

        ColumnGapStripper() throws java.io.IOException {
            super();
        }

        @Override
        protected void writeString(String text, java.util.List<org.apache.pdfbox.text.TextPosition> positions) throws java.io.IOException {
            if (!positions.isEmpty()) {
                org.apache.pdfbox.text.TextPosition first = positions.getFirst();
                float charWidth = Math.max(first.getWidthOfSpace(), first.getWidth());
                if (!Float.isNaN(lastEndX) && charWidth > 0 && first.getXDirAdj() - lastEndX > 2.5f * charWidth) {
                    writeString(COLUMN_GAP);
                }
                org.apache.pdfbox.text.TextPosition last = positions.getLast();
                lastEndX = last.getXDirAdj() + last.getWidthDirAdj();
            }
            super.writeString(text, positions);
        }

        @Override
        protected void writeLineSeparator() throws java.io.IOException {
            lastEndX = Float.NaN;
            super.writeLineSeparator();
        }

        @Override
        protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws java.io.IOException {
            lastEndX = Float.NaN;
            super.startPage(page);
        }
    }
}
