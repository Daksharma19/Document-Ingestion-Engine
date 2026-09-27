package com.docextract.eval.corpus;

import com.docextract.document.Document;
import com.docextract.validation.ValidationEngine;
import com.docextract.validation.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads a corpus directory where each document {@code <name>.<ext>} has a sibling
 * {@code <name>.labels.json} of the form {"schema": {...}, "expected": {...}}.
 */
public class CorpusLoader {

    static final String LABELS_SUFFIX = ".labels.json";

    private final ObjectMapper json;
    private final ValidationEngine schemaOnly = new ValidationEngine(List.of());

    public CorpusLoader(ObjectMapper json) {
        this.json = json;
    }

    public List<LabeledDocument> load(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new CorpusException("corpus directory not found: " + dir.toAbsolutePath());
        }
        List<Path> labelFiles;
        try (Stream<Path> files = Files.list(dir)) {
            labelFiles = files.filter(p -> p.getFileName().toString().endsWith(LABELS_SUFFIX)).sorted().toList();
        }
        List<LabeledDocument> docs = new ArrayList<>();
        for (Path labels : labelFiles) {
            docs.add(loadOne(dir, labels));
        }
        return docs;
    }

    private LabeledDocument loadOne(Path dir, Path labelsFile) throws IOException {
        String fileName = labelsFile.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - LABELS_SUFFIX.length());
        Path docPath = findDocument(dir, name);

        JsonNode labels = json.readTree(labelsFile.toFile());
        JsonNode schema = labels.get("schema");
        JsonNode expected = labels.get("expected");
        if (schema == null || !schema.isObject() || expected == null || !expected.isObject()) {
            throw new CorpusException(fileName + ": must contain object fields 'schema' and 'expected'");
        }
        ValidationReport report = schemaOnly.validate(expected, schema);
        if (!report.isValid()) {
            throw new CorpusException(fileName + ": 'expected' does not satisfy its own schema: " + report.violations());
        }
        Document document = new Document(docPath.getFileName().toString(), null, Files.readAllBytes(docPath));
        return new LabeledDocument(name, document, schema, expected);
    }

    private static Path findDocument(Path dir, String name) throws IOException {
        List<Path> matches;
        try (Stream<Path> files = Files.list(dir)) {
            matches = files.filter(p -> {
                String f = p.getFileName().toString();
                return f.startsWith(name + ".") && !f.endsWith(LABELS_SUFFIX);
            }).toList();
        }
        if (matches.size() != 1) {
            throw new CorpusException("expected exactly one document named " + name + ".* in " + dir + ", found " + matches.size());
        }
        return matches.getFirst();
    }

    public static class CorpusException extends RuntimeException {
        public CorpusException(String message) {
            super(message);
        }
    }
}
