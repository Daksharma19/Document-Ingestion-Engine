package com.docextract.eval.corpus;

import com.docextract.document.Document;
import com.fasterxml.jackson.databind.JsonNode;

public record LabeledDocument(String name, Document document, JsonNode schema, JsonNode expected) {
}
