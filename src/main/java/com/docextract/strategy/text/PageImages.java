package com.docextract.strategy.text;

import com.docextract.document.Document;
import com.docextract.document.DocumentKind;
import com.docextract.strategy.ExtractionException;
import com.docextract.strategy.UnsupportedDocumentException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** Turns a PDF or image document into page images for the OCR and vision tiers. */
public final class PageImages {

    /** 300 DPI is Tesseract's recommended input resolution. */
    public static final int OCR_DPI = 300;

    private PageImages() {
    }

    public static List<BufferedImage> render(Document document, DocumentKind kind, int dpi, int maxPages) {
        try {
            return switch (kind) {
                case PDF -> renderPdf(document, dpi, maxPages);
                case IMAGE -> List.of(readImage(document));
                default -> throw new UnsupportedDocumentException(
                        "cannot produce a page image from " + document.filename() + " (" + kind + ")");
            };
        } catch (UnsupportedDocumentException | ExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtractionException("could not render " + document.filename(), e);
        }
    }

    private static List<BufferedImage> renderPdf(Document document, int dpi, int maxPages) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(document.bytes())) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            int pages = Math.min(pdf.getNumberOfPages(), maxPages);
            List<BufferedImage> out = new ArrayList<>(pages);
            for (int i = 0; i < pages; i++) {
                out.add(renderer.renderImageWithDPI(i, dpi, ImageType.GRAY));
            }
            return out;
        }
    }

    private static BufferedImage readImage(Document document) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(document.bytes()));
        if (img == null) {
            throw new UnsupportedDocumentException("no image reader for " + document.filename());
        }
        return img;
    }
}
