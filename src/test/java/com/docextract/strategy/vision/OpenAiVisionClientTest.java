package com.docextract.strategy.vision;

import com.docextract.document.Document;
import com.docextract.document.DocumentTypeDetector;
import com.docextract.strategy.ExtractionException;
import com.docextract.strategy.ExtractionResult;
import com.docextract.strategy.TierNotConfiguredException;
import com.docextract.strategy.VisionLlmStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real HTTP client against a local stand-in server, to check the request we send and how we
 * parse a reply. This is a test double only: it says nothing about the real model's accuracy.
 */
class OpenAiVisionClientTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastRequest.set(json.readTree(ex.getRequestBody()));
            byte[] body = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private VisionLlmStrategy strategy(String key) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new VisionLlmStrategy(new OpenAiVisionClient(json, key, base, "gpt-4o-mini", 10),
                new DocumentTypeDetector(), json, 3);
    }

    private static Document scan() throws Exception {
        return new Document("scanned-003.png", null, Files.readAllBytes(Path.of("corpus/scanned-003.png")));
    }

    private JsonNode schema() throws Exception {
        return json.readTree("{\"type\":\"object\",\"properties\":{\"invoiceNumber\":{\"type\":\"string\"},"
                + "\"total\":{\"type\":\"number\"},\"vendor\":{\"type\":\"string\"}}}");
    }

    @Test
    void sendsImageAndSchemaInJsonModeAndProjectsReplyOntoSchema() throws Exception {
        reply = """
                {"choices":[{"message":{"content":"{\\"invoiceNumber\\":\\"BE-7781\\",\\"total\\":312.5,\\"vendor\\":null,\\"extra\\":1}"}}],
                 "usage":{"prompt_tokens":812,"completion_tokens":21}}""";
        ExtractionResult r = strategy("sk-test").extract(scan(), schema());

        JsonNode req = lastRequest.get();
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
        assertThat(req.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(req.path("response_format").path("type").asText()).isEqualTo("json_object");
        JsonNode content = req.path("messages").path(1).path("content");
        assertThat(content.path(0).path("text").asText()).contains("\"invoiceNumber\"");
        assertThat(content.path(1).path("image_url").path("url").asText()).startsWith("data:image/png;base64,iVBOR");

        assertThat(r.fields()).containsEntry("invoiceNumber", "BE-7781").containsEntry("vendor", null)
                .doesNotContainKey("extra");
        assertThat(r.confidence()).containsEntry("vendor", 0.0);
        assertThat(r.rawText()).contains("promptTokens=812");
        assertThat(r.costUnits()).isEqualTo(100);
    }

    @Test
    void toleratesAFencedJsonReply() throws Exception {
        reply = "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"total\\\": 1}\\n```\"}}]}";
        assertThat(strategy("k").extract(scan(), schema()).fields()).containsEntry("total", 1);
    }

    @Test
    void httpErrorBecomesExtractionError() throws Exception {
        status = 401;
        reply = "{\"error\":{\"message\":\"Incorrect API key\"}}";
        assertThatThrownBy(() -> strategy("bad").extract(scan(), schema()))
                .isInstanceOf(ExtractionException.class).hasMessageContaining("401");
    }

    @Test
    void withoutAKeyTheTierIsNotConfiguredAndMakesNoCall() throws Exception {
        assertThatThrownBy(() -> strategy("").extract(scan(), schema()))
                .isInstanceOf(TierNotConfiguredException.class).hasMessageContaining("OPENAI_API_KEY");
        assertThat(lastRequest.get()).isNull();
    }
}
