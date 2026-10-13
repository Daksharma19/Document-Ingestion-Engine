package com.docextract.strategy.text;

import com.docextract.document.Document;
import com.docextract.strategy.ExtractionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/** PDFs go through PDFBox directly; every other format goes through Tika's auto-detection. */
@Component
public class TextExtractor {

    /** Fewer letters/digits than this per page means the PDF has no usable text layer (likely a scan). */
    static final int MIN_CHARS_PER_PAGE = 20;

    private final Tika tika;

    public TextExtractor() {
        this.tika = new Tika();
        this.tika.setMaxStringLength(10_000_000);
    }

    public String extractText(Document document) {
        try {
            return document.isPdf() ? pdfText(document) : tika.parseToString(new ByteArrayInputStream(document.bytes()));
        } catch (Exception e) {
            throw new ExtractionException("could not extract text from " + document.filename(), e);
        }
    }

    /** True when the PDF's embedded text layer carries real content on average across its pages. */
    public boolean hasTextLayer(Document document) {
        try (PDDocument pdf = Loader.loadPDF(document.bytes())) {
            int pages = Math.max(1, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf);
            long meaningful = text.chars().filter(Character::isLetterOrDigit).count();
            return meaningful >= (long) MIN_CHARS_PER_PAGE * pages;
        } catch (Exception e) {
            throw new ExtractionException("could not read PDF " + document.filename(), e);
        }
    }

    private String pdfText(Document document) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(document.bytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(pdf);
        }
    }
}
