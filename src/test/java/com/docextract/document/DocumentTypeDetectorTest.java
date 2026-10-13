package com.docextract.document;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTypeDetectorTest {

    private final DocumentTypeDetector detector = new DocumentTypeDetector();

    private DocumentKind kindOf(String corpusFile, String claimedName) throws Exception {
        return detector.detect(new Document(claimedName, null, Files.readAllBytes(Path.of("corpus", corpusFile)))).kind();
    }

    @Test
    void detectsFromContentNotTheClaimedName() throws Exception {
        assertThat(kindOf("invoice-001.pdf", "invoice-001.pdf")).isEqualTo(DocumentKind.PDF);
        assertThat(kindOf("scanned-003.png", "scanned-003.png")).isEqualTo(DocumentKind.IMAGE);
        assertThat(kindOf("receipt-002.docx", "receipt-002.docx")).isEqualTo(DocumentKind.OFFICE_OR_TEXT);
        // A PNG uploaded with a misleading name is still an image.
        assertThat(kindOf("scanned-003.png", "upload.pdf")).isEqualTo(DocumentKind.IMAGE);
    }

    @Test
    void plainTextIsTextAndRandomBytesAreUnknown() {
        assertThat(detector.detect(new Document("a.txt", null, "Total: 1".getBytes())).kind())
                .isEqualTo(DocumentKind.OFFICE_OR_TEXT);
        assertThat(DocumentTypeDetector.kindOf("application/octet-stream")).isEqualTo(DocumentKind.UNKNOWN);
    }
}
