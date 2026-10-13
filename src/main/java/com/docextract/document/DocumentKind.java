package com.docextract.document;

/** Coarse family of a document, used to route it to a tier that can read it. */
public enum DocumentKind {
    PDF,
    IMAGE,
    /** Word, spreadsheets, HTML, plain text, ... : anything Tika can pull text from directly. */
    OFFICE_OR_TEXT,
    UNKNOWN
}
