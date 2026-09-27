package com.docextract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DocextractApplicationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void extractReturnsStubShape() throws Exception {
        var file = new MockMultipartFile("file", "a.txt", "text/plain", "Total: 42".getBytes());
        mvc.perform(multipart("/api/extract").file(file)
                        .param("schema", "{\"type\":\"object\",\"properties\":{\"total\":{\"type\":\"number\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields").exists())
                .andExpect(jsonPath("$.confidence.total").value(0.0))
                .andExpect(jsonPath("$.tier").value("STUB"))
                .andExpect(jsonPath("$.costUnits").value(0));
    }

    @Test
    void badSchemaIs400() throws Exception {
        var file = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes());
        mvc.perform(multipart("/api/extract").file(file).param("schema", "not json"))
                .andExpect(status().isBadRequest());
    }
}
