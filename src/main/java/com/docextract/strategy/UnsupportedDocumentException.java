package com.docextract.strategy;

/** The tier cannot read this kind of document (e.g. OCR on a DOCX, which has no page image). */
public class UnsupportedDocumentException extends RuntimeException {
    public UnsupportedDocumentException(String message) {
        super(message);
    }
}
