package com.docextract.strategy.ocr;

import com.docextract.strategy.ExtractionException;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Thin wrapper over Tess4J. Tesseract instances are not thread-safe, so each call gets its own. */
@Component
public class OcrEngine {

    private final Path tessdata;
    private final String language;

    public OcrEngine(@Value("${docextract.ocr.tessdata-path}") String tessdata,
                     @Value("${docextract.ocr.language:eng}") String language) {
        this.tessdata = Path.of(tessdata).toAbsolutePath();
        this.language = language;
    }

    public Optional<String> notConfiguredReason() {
        Path model = tessdata.resolve(language + ".traineddata");
        return Files.isRegularFile(model) ? Optional.empty()
                : Optional.of("OCR language data not found at " + model + " (set docextract.ocr.tessdata-path or TESSDATA_PREFIX)");
    }

    public String recognise(List<BufferedImage> pages) {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessdata.toString());
        tesseract.setLanguage(language);
        // Page segmentation mode 6 (one uniform block) keeps "Label: value" pairs on one line for the mapper.
        tesseract.setPageSegMode(6);
        // Keeps inter-word spacing so column layouts stay readable.
        tesseract.setVariable("preserve_interword_spaces", "1");
        StringBuilder text = new StringBuilder();
        try {
            for (BufferedImage page : pages) {
                text.append(tesseract.doOCR(ImagePreprocessor.clean(page))).append('\n');
            }
        } catch (TesseractException | Error e) {
            // Error covers UnsatisfiedLinkError when the native library cannot load.
            throw new ExtractionException("OCR failed: " + e.getMessage(), e);
        }
        return text.toString();
    }
}
