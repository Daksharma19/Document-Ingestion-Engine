package com.docextract.strategy;

import com.docextract.document.ContentCache;
import com.docextract.document.Document;
import com.docextract.document.DocumentKind;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.strategy.mapping.FieldMapper;
import com.docextract.strategy.ocr.OcrEngine;
import com.docextract.strategy.ocr.PythonOcrClient;
import com.docextract.strategy.text.PageImages;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads page images with OCR, then maps fields from the OCR text with the same mapper as native text.
 * Two engines:
 * <ul>
 *   <li><b>python</b>: the {@code ocr-service} (RapidOCR / PP-OCR). Far more accurate on logos, small print,
 *       currency symbols and photocopies; Java only routes the document to it.</li>
 *   <li><b>tesseract</b>: in-process Tess4J, the fallback when the service is not running.</li>
 * </ul>
 * {@code docextract.ocr.engine=auto} (default) uses the service when it is up and falls back to Tesseract.
 */
@Component
public class OcrStrategy implements ExtractionStrategy {

    public enum Engine { AUTO, PYTHON, TESSERACT }

    private static final Logger log = LoggerFactory.getLogger(OcrStrategy.class);

    private final OcrEngine tesseract;
    private final PythonOcrClient python;
    private final Engine engine;
    private final FieldMapper fieldMapper;
    private final DocumentTypeDetector detector;
    private final int maxPages;
    private final int dpi;
    private final ContentCache cache;

    /** OCR output plus the engine that produced it, as cached per document. */
    private record Read(String text, String engine, double confidence) {
    }

    /** Tesseract only (tests and deployments without the Python service). */
    public OcrStrategy(OcrEngine ocr, FieldMapper fieldMapper, DocumentTypeDetector detector, int maxPages) {
        this(ocr, null, "tesseract", fieldMapper, detector, maxPages, PageImages.OCR_DPI, new ContentCache(0));
    }

    @Autowired
    public OcrStrategy(OcrEngine tesseract, PythonOcrClient python,
                       @Value("${docextract.ocr.engine:auto}") String engine,
                       FieldMapper fieldMapper, DocumentTypeDetector detector,
                       @Value("${docextract.ocr.max-pages:10}") int maxPages,
                       @Value("${docextract.ocr.dpi:300}") int dpi,
                       ContentCache cache) {
        this.tesseract = tesseract;
        this.python = python;
        this.engine = Engine.valueOf(engine.trim().toUpperCase(Locale.ROOT));
        this.fieldMapper = fieldMapper;
        this.detector = detector;
        this.maxPages = maxPages;
        this.dpi = dpi;
        this.cache = cache;
    }

    @Override
    public ExtractionResult extract(Document document, JsonNode schema) {
        // A document read before is answered straight from the cache: no health check, no call.
        if (engine != Engine.TESSERACT && python != null) {
            java.util.Optional<Read> hit = cache.peek(document, pythonKey());
            if (hit.isPresent()) {
                return mapped(hit.get().text(), schema, hit.get().engine(), hit.get().confidence());
            }
        }
        notConfiguredReason().ifPresent(r -> {
            throw new TierNotConfiguredException(r);
        });
        DocumentKind kind = detector.detect(document).kind();
        if (!supports(kind)) {
            throw new UnsupportedDocumentException("OCR needs a PDF or image, got " + kind + " for " + document.filename());
        }
        // OCR is the slow part (seconds per page); the same file seen again is answered from the cache.
        if (usePython()) {
            try {
                Read read = cache.get(document, pythonKey(), () -> {
                    PythonOcrClient.OcrText ocr = python.recognise(document, dpi, maxPages);
                    return new Read(ocr.text(), ocr.engine(), ocr.meanConfidence());
                });
                return mapped(read.text(), schema, read.engine(), read.confidence());
            } catch (ExtractionException e) {
                if (engine == Engine.PYTHON || tesseract.notConfiguredReason().isPresent()) {
                    throw e;
                }
                log.warn("OCR service failed for {}, falling back to Tesseract: {}", document.filename(), e.getMessage());
            }
        }
        String text = cache.get(document, "ocr:tesseract:" + dpi + ":" + maxPages,
                () -> tesseract.recognise(PageImages.render(document, kind, dpi, maxPages)));
        return mapped(text, schema, "tesseract", 1.0);
    }

    private String pythonKey() {
        return "ocr:python:" + dpi + ":" + maxPages;
    }

    private ExtractionResult mapped(String text, JsonNode schema, String engineName, double ocrConfidence) {
        FieldMapper.MappedFields mapped = fieldMapper.map(text, schema);
        Map<String, Double> confidence = mapped.confidence();
        if (ocrConfidence > 0 && ocrConfidence < 1) {
            // A field read from shaky OCR is less trustworthy than the same field from a crisp page.
            Map<String, Double> scaled = new LinkedHashMap<>();
            confidence.forEach((k, v) -> scaled.put(k, v == null ? null : Math.round(v * ocrConfidence * 1000) / 1000.0));
            confidence = scaled;
        }
        return new ExtractionResult(mapped.fields(), confidence, text, costTier().costUnits(), engineName, ocrConfidence);
    }

    @Override
    public Optional<ExtractionResult> remap(ExtractionResult previous, JsonNode schema) {
        double conf = previous.textConfidence() == null ? 1.0 : previous.textConfidence();
        return Optional.of(mapped(previous.rawText(), schema, previous.engine(), conf));
    }

    private boolean usePython() {
        return python != null && switch (engine) {
            case PYTHON -> true;
            case TESSERACT -> false;
            case AUTO -> python.isAvailable();
        };
    }

    @Override
    public boolean supports(DocumentKind kind) {
        return kind == DocumentKind.PDF || kind == DocumentKind.IMAGE;
    }

    @Override
    public Optional<String> notConfiguredReason() {
        return switch (engine) {
            case TESSERACT -> tesseract.notConfiguredReason();
            case PYTHON -> python != null && python.isAvailable() ? Optional.empty()
                    : Optional.of("OCR service not reachable at " + (python == null ? "(none)" : python.baseUrl())
                    + " (start ocr-service, see README)");
            case AUTO -> python != null && python.isAvailable() ? Optional.empty()
                    : tesseract.notConfiguredReason().map(r -> r + "; the Python OCR service is not running either");
        };
    }

    @Override
    public CostTier costTier() {
        return CostTier.OCR;
    }

    @Override
    public String name() {
        return "ocr";
    }
}
