package com.docextract.api;

import com.docextract.validation.RuleResult;
import com.docextract.validation.Violation;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Result for one uploaded file. A failure on one file never fails the batch: it shows up here as
 * {@code status} != OK with an {@code error}, and {@code fields} is empty.
 *
 * @param fieldValidation per top-level schema property: true when no violation implicates the field
 * @param routing         why this tier was chosen (auto routing) or "requested"
 * @param engine          what read the document inside the tier (OCR: "rapidocr-ppocr" or "tesseract")
 * @param schemaId        the built-in schema used, when the request named one or asked for {@code auto}
 * @param documentType    with {@code schema=auto}: the document type the classifier picked
 * @param elapsedMs       wall-clock time spent on this file (reading, mapping, validation, any escalation)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileExtractionResult(
        String filename,
        String detectedType,
        String documentKind,
        Status status,
        String tier,
        String strategy,
        String routing,
        String engine,
        String schemaId,
        String documentType,
        int costUnits,
        long elapsedMs,
        Map<String, Object> fields,
        Map<String, Double> confidence,
        Map<String, Boolean> fieldValidation,
        Boolean valid,
        List<Violation> violations,
        List<RuleResult> rules,
        String error,
        /* The text the tier read (native or OCR); only returned when includeText=true. */
        String text) {

    public enum Status {
        OK,
        /** The tier needs configuration (e.g. an API key) that this deployment lacks. */
        TIER_NOT_CONFIGURED,
        /** The tier cannot read this kind of document. */
        UNSUPPORTED,
        /** The document could not be read or the tier failed. */
        ERROR
    }
}
