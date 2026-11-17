package com.docextract.parse.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The parse of one uploaded file. {@code status} is OK or ERROR; one bad file never fails a batch. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParsedDocument(String filename, String mediaType, String kind, String status, String error,
                             List<ParsedPage> pages, String markdown, int costUnits, long elapsedMs) {

    public static ParsedDocument error(String filename, String mediaType, String kind, String error) {
        return new ParsedDocument(filename, mediaType, kind, "ERROR", error, List.of(), null, 0, 0);
    }

    public ParsedDocument withElapsed(long ms) {
        return new ParsedDocument(filename, mediaType, kind, status, error, pages, markdown, costUnits, ms);
    }
}
