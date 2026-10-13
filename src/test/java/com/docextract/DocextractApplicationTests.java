package com.docextract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "docextract.vision.api-key=")
@AutoConfigureMockMvc
class DocextractApplicationTests {

    private static final String TOTAL_SCHEMA = "{\"type\":\"object\",\"properties\":{\"total\":{\"type\":\"number\"}}}";

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void singleLegacyFilePartStillWorks() throws Exception {
        var file = new MockMultipartFile("file", "a.txt", "text/plain", "Total: 42".getBytes());
        mvc.perform(multipart("/api/extract").file(file).param("schema", TOTAL_SCHEMA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("OK"))
                .andExpect(jsonPath("$[0].fields.total").value(42))
                .andExpect(jsonPath("$[0].confidence.total").value(0.8))
                .andExpect(jsonPath("$[0].fieldValidation.total").value(true))
                .andExpect(jsonPath("$[0].tier").value("NATIVE_TEXT"))
                .andExpect(jsonPath("$[0].costUnits").value(1));
    }

    @Test
    void multipleFilesGiveOneResultEachInOrder() throws Exception {
        byte[] pdf = Files.readAllBytes(Path.of("corpus/invoice-001.pdf"));
        String schema = """
                {"type":"object","required":["invoiceNumber","total"],
                 "properties":{"invoiceNumber":{"type":"string"},"total":{"type":"number"},
                   "subtotal":{"type":"number"},"tax":{"type":"number"}}}""";
        mvc.perform(multipart("/api/extract")
                        .file(new MockMultipartFile("files", "invoice-001.pdf", "application/pdf", pdf))
                        .file(new MockMultipartFile("files", "b.txt", "text/plain", "Total: 7".getBytes()))
                        .file(new MockMultipartFile("files", "empty.txt", "text/plain", new byte[0]))
                        .param("schema", schema)
                        .param("rules", "[\"subtotal + tax == total\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].filename").value("invoice-001.pdf"))
                .andExpect(jsonPath("$[0].detectedType").value("application/pdf"))
                .andExpect(jsonPath("$[0].fields.invoiceNumber").value("INV-2024-001"))
                .andExpect(jsonPath("$[0].fields.total").value(165.20))
                .andExpect(jsonPath("$[0].rules[0].outcome").value("PASSED"))
                .andExpect(jsonPath("$[0].valid").value(true))
                .andExpect(jsonPath("$[1].fields.total").value(7))
                .andExpect(jsonPath("$[1].fieldValidation.invoiceNumber").value(false))
                .andExpect(jsonPath("$[1].rules[0].outcome").value("SKIPPED"))
                .andExpect(jsonPath("$[2].status").value("ERROR"));
    }

    @Test
    void visionTierWithoutKeyReportsNotConfigured() throws Exception {
        byte[] png = Files.readAllBytes(Path.of("corpus/scanned-003.png"));
        mvc.perform(multipart("/api/extract")
                        .file(new MockMultipartFile("files", "scanned-003.png", "image/png", png))
                        .param("schema", TOTAL_SCHEMA).param("tier", "vision-llm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("TIER_NOT_CONFIGURED"))
                .andExpect(jsonPath("$[0].fields").isEmpty());
    }

    @Test
    void badSchemaIs400() throws Exception {
        var file = new MockMultipartFile("files", "a.txt", "text/plain", "x".getBytes());
        mvc.perform(multipart("/api/extract").file(file).param("schema", "not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void badRuleIs400() throws Exception {
        var file = new MockMultipartFile("files", "a.txt", "text/plain", "x".getBytes());
        mvc.perform(multipart("/api/extract").file(file).param("schema", TOTAL_SCHEMA).param("rules", "[\"total ==\"]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noFilesIs400() throws Exception {
        mvc.perform(multipart("/api/extract").param("schema", TOTAL_SCHEMA))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownTierIs400() throws Exception {
        var file = new MockMultipartFile("files", "a.txt", "text/plain", "x".getBytes());
        mvc.perform(multipart("/api/extract").file(file).param("schema", TOTAL_SCHEMA).param("tier", "magic"))
                .andExpect(status().isBadRequest());
    }
}
