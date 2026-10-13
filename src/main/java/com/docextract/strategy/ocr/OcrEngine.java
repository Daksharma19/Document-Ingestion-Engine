package com.docextract.strategy.ocr;

import com.docextract.strategy.ExtractionException;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Thin wrapper over Tess4J. Tesseract instances are not thread-safe, so each call gets its own. */
@Component
public class OcrEngine {

    /** How page images are cleaned before recognition. */
    public enum Preprocess {
        /** Clean only pages that look like noisy scans (see {@link ImagePreprocessor#isNoisy}). */
        AUTO,
        ALWAYS,
        NEVER
    }

    private final Path tessdata;
    private final String language;
    private final int pageSegMode;
    private final Preprocess preprocess;
    private final boolean layoutClean;

    public OcrEngine(String tessdata, String language) {
        this(tessdata, language, 6, "auto", true);
    }

    @Autowired
    public OcrEngine(@Value("${docextract.ocr.tessdata-path}") String tessdata,
                     @Value("${docextract.ocr.language:eng}") String language,
                     @Value("${docextract.ocr.page-seg-mode:6}") int pageSegMode,
                     @Value("${docextract.ocr.preprocess:auto}") String preprocess,
                     @Value("${docextract.ocr.layout-clean:true}") boolean layoutClean) {
        this.tessdata = Path.of(tessdata).toAbsolutePath();
        this.language = language;
        this.pageSegMode = pageSegMode;
        this.preprocess = Preprocess.valueOf(preprocess.trim().toUpperCase(Locale.ROOT));
        this.layoutClean = layoutClean;
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
        tesseract.setPageSegMode(pageSegMode);
        // Keeps inter-word spacing so column layouts stay readable.
        tesseract.setVariable("preserve_interword_spaces", "1");
        StringBuilder text = new StringBuilder();
        try {
            for (BufferedImage page : pages) {
                text.append(tesseract.doOCR(prepare(page))).append('\n');
            }
        } catch (TesseractException | Error e) {
            // Error covers UnsatisfiedLinkError when the native library cannot load.
            throw new ExtractionException("OCR failed: " + e.getMessage(), e);
        }
        return removeRuleArtifacts(text.toString());
    }

    /**
     * Short table borders that survive clean-up come back as lone "|" tokens ("| TOTAL | $313.90 |"). They are
     * dropped, keeping the gap they stood in so column structure is preserved.
     */
    static String removeRuleArtifacts(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            String l = line.replaceAll("(?<=^|\\s)[|¦](?=\\s|$)", " ");
            // Tight kerning splits "JavaScript/TypeScript" into "JavaScript /TypeScript".
            l = l.replaceAll("(?<=\\p{L}) /(?=\\p{L})", "/");
            out.append(l.strip()).append('\n');
        }
        return out.toString();
    }

    private BufferedImage prepare(BufferedImage page) {
        BufferedImage p = switch (preprocess) {
            case ALWAYS -> ImagePreprocessor.clean(page);
            case NEVER -> page;
            case AUTO -> ImagePreprocessor.isNoisy(page) ? ImagePreprocessor.clean(page) : page;
        };
        return layoutClean ? ImagePreprocessor.layoutClean(p) : p;
    }
}
