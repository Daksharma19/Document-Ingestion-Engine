package com.docextract.eval.corpus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusLoaderTest {

    private final CorpusLoader loader = new CorpusLoader(new ObjectMapper());

    private static final String LABELS = """
            {"schema":{"type":"object","properties":{"total":{"type":"number"}}},
             "expected":{"total":10}}""";

    @Test
    void loadsTheCheckedInSampleCorpus() throws Exception {
        var docs = loader.load(Path.of("corpus"));
        assertThat(docs).extracting(LabeledDocument::name)
                .containsExactly("invoice-001", "receipt-002", "scanned-003");
        assertThat(docs).allSatisfy(d -> assertThat(d.document().bytes()).isNotEmpty());
    }

    @Test
    void pairsLabelsWithDocumentOfAnyExtension(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.labels.json"), LABELS);
        Files.writeString(dir.resolve("a.txt"), "Total: 10");
        var doc = loader.load(dir).getFirst();
        assertThat(doc.document().filename()).isEqualTo("a.txt");
        assertThat(doc.expected().get("total").asInt()).isEqualTo(10);
    }

    @Test
    void rejectsLabelsWithoutDocument(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.labels.json"), LABELS);
        assertThatThrownBy(() -> loader.load(dir)).hasMessageContaining("exactly one document");
    }

    @Test
    void rejectsExpectedValuesThatViolateTheirSchema(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.labels.json"), LABELS.replace("\"total\":10", "\"total\":\"ten\""));
        Files.writeString(dir.resolve("a.txt"), "x");
        assertThatThrownBy(() -> loader.load(dir)).hasMessageContaining("does not satisfy its own schema");
    }
}
