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

    private String pdfText(Document document) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(document.bytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(pdf);
        }
    }
}
