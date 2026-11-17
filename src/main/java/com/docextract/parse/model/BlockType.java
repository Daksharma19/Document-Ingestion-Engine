package com.docextract.parse.model;

public enum BlockType {
    HEADING, PARAGRAPH, LIST_ITEM, TABLE,
    /** Text OCR found inside an image (diagram labels, chart axes, a screenshot). */
    FIGURE
}
