package com.docextract.document;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/** Detects the real media type from the bytes (magic numbers first, filename as a hint), not the client's claim. */
@Component
public class DocumentTypeDetector {

    public record DetectedType(String mediaType, DocumentKind kind) {
    }

    private final Tika tika = new Tika();

    public DetectedType detect(Document document) {
        String mediaType = tika.detect(document.bytes(), document.filename());
        return new DetectedType(mediaType, kindOf(mediaType));
    }

    static DocumentKind kindOf(String mediaType) {
        if (mediaType == null || mediaType.equals("application/octet-stream")) {
            return DocumentKind.UNKNOWN;
        }
        if (mediaType.equals("application/pdf")) {
            return DocumentKind.PDF;
        }
        if (mediaType.startsWith("image/")) {
            return DocumentKind.IMAGE;
        }
        return DocumentKind.OFFICE_OR_TEXT;
    }
}
