package com.docextract.strategy.vision;

import com.docextract.strategy.ExtractionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Calls the OpenAI Chat Completions API with page images + the caller's JSON Schema, in JSON mode. */
@Component
public class OpenAiVisionClient {

    static final String SYSTEM_PROMPT = """
            You extract structured data from document images.
            Return ONLY a JSON object whose keys are the top-level properties of the given JSON Schema.
            Use the types the schema declares; dates with format "date" as YYYY-MM-DD; numbers without
            currency symbols or thousands separators. If a value is not visible in the document, use null.
            Never guess or invent values.""";

    public record VisionReply(JsonNode fields, int promptTokens, int completionTokens) {
    }

    private final ObjectMapper json;
    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;

    public OpenAiVisionClient(ObjectMapper json,
                              @Value("${docextract.vision.api-key:}") String apiKey,
                              @Value("${docextract.vision.base-url}") String baseUrl,
                              @Value("${docextract.vision.model}") String model,
                              @Value("${docextract.vision.timeout-seconds:90}") int timeoutSeconds) {
        this.json = json;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public Optional<String> notConfiguredReason() {
        return apiKey.isEmpty()
                ? Optional.of("tier not configured: set OPENAI_API_KEY (docextract.vision.api-key) to enable the vision-LLM tier")
                : Optional.empty();
    }

    public String model() {
        return model;
    }

    public VisionReply extract(List<BufferedImage> pages, JsonNode schema) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(requestBody(pages, schema))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ExtractionException("vision API returned HTTP " + response.statusCode() + ": "
                        + abbreviate(response.body()), null);
            }
            return parseReply(json.readTree(response.body()));
        } catch (IOException e) {
            throw new ExtractionException("vision API call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("vision API call interrupted", e);
        }
    }

    ObjectNode requestBody(List<BufferedImage> pages, JsonNode schema) throws IOException {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        body.putObject("response_format").put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        ObjectNode user = messages.addObject().put("role", "user");
        ArrayNode content = user.putArray("content");
        content.addObject().put("type", "text").put("text", "JSON Schema:\n" + json.writeValueAsString(schema));
        for (BufferedImage page : pages) {
            content.addObject().put("type", "image_url").putObject("image_url")
                    .put("url", "data:image/png;base64," + pngBase64(page))
                    .put("detail", "high");
        }
        return body;
    }

    VisionReply parseReply(JsonNode reply) throws IOException {
        JsonNode message = reply.path("choices").path(0).path("message");
        if (message.hasNonNull("refusal")) {
            throw new ExtractionException("vision model refused: " + message.get("refusal").asText(), null);
        }
        String text = message.path("content").asText("");
        // JSON mode should return bare JSON, but tolerate a markdown code fence around it.
        text = text.replaceAll("(?s)^\\s*`{3}(?:json)?\\s*|\\s*`{3}\\s*$", "");
        JsonNode fields = text.isBlank() ? null : json.readTree(text);
        if (fields == null || !fields.isObject()) {
            throw new ExtractionException("vision model did not return a JSON object: " + abbreviate(text), null);
        }
        JsonNode usage = reply.path("usage");
        return new VisionReply(fields, usage.path("prompt_tokens").asInt(), usage.path("completion_tokens").asInt());
    }

    private static String pngBase64(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
