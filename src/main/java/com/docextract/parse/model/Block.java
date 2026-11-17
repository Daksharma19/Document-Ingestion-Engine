package com.docextract.parse.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One structural unit of a page. {@code level} is set for headings (1 = top), {@code rows} for tables
 * (first row is the header when the source had one). {@code bbox} is null when the source has no geometry (DOCX).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Block(BlockType type, String text, Integer level, List<List<String>> rows, BBox bbox,
                    double confidence, PageMethod method) {

    public Block withConfidence(double c) {
        return new Block(type, text, level, rows, bbox, Math.round(c * 1000) / 1000.0, method);
    }

    public static Block heading(String text, int level, BBox bbox, PageMethod method) {
        return new Block(BlockType.HEADING, text, level, null, bbox, 1.0, method);
    }

    public static Block paragraph(String text, BBox bbox, PageMethod method) {
        return new Block(BlockType.PARAGRAPH, text, null, null, bbox, 1.0, method);
    }

    public static Block listItem(String text, BBox bbox, PageMethod method) {
        return new Block(BlockType.LIST_ITEM, text, null, null, bbox, 1.0, method);
    }

    public static Block figure(String text, BBox bbox, double confidence) {
        return new Block(BlockType.FIGURE, text, null, null, bbox, Math.round(confidence * 1000) / 1000.0, PageMethod.OCR);
    }

    public static Block table(List<List<String>> rows, BBox bbox, PageMethod method) {
        String text = String.join("\n", rows.stream().map(r -> String.join(" | ", r)).toList());
        return new Block(BlockType.TABLE, text, null, rows, bbox, 1.0, method);
    }
}
