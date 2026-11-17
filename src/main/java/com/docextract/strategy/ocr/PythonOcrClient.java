package com.docextract.strategy.ocr;

import com.docextract.document.Document;
import com.docextract.strategy.ExtractionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client for the Python OCR service ({@code ocr-service/}: RapidOCR with PP-OCR models). The service renders the
 * PDF/image itself and returns layout-preserving text in the same shape as the PDF text layer (3+ spaces between
 * columns), so the Java mapper works on it unchanged.
 */
@Component
public class PythonOcrClient {

    /** What the service read, with its mean recognition confidence (0..1). */
    public record OcrText(String text, String engine, double meanConfidence) {
    }

    /** One OCR'd line in page pixels at the request's DPI. */
    public record OcrLine(String text, double x0, double y0, double x1, double y1, double height, double confidence) {
    }

    /** One OCR'd page; {@code dpi} is the resolution the pixel coordinates refer to. */
    public record OcrPage(int width, int height, int dpi, double meanConfidence, List<OcrLine> lines,
                          List<OcrLine> boxes) {
    }

    private static final Duration HEALTH_TTL = Duration.ofSeconds(10);

    private final ObjectMapper json;
    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;
    private volatile long healthCheckedAt = Long.MIN_VALUE;
    private volatile boolean healthy;

    public PythonOcrClient(ObjectMapper json,
                           @Value("${docextract.ocr.service-url:http://127.0.0.1:8001}") String baseUrl,
                           @Value("${docextract.ocr.service-timeout-seconds:180}") int timeoutSeconds) {
        this.json = json;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        // HTTP/1.1: uvicorn rejects the h2c upgrade Java attempts by default (and the upgrade garbles the POST body).
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * True unless the service is known to be down. Cached briefly so batches don't ping per file. A /health that is
     * merely slow means the service is busy recognising other documents, not down: treating it as down silently
     * sent those files to Tesseract under load. Only a refused connection or an error status counts as down.
     */
    public boolean isAvailable() {
        if (baseUrl.isEmpty()) {
            return false;
        }
        long now = System.nanoTime();
        if (healthCheckedAt != Long.MIN_VALUE && now - healthCheckedAt < HEALTH_TTL.toNanos()) {
            return healthy;
        }
        boolean up;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            up = r.statusCode() == 200 && "UP".equals(json.readTree(r.body()).path("status").asText());
        } catch (java.net.http.HttpTimeoutException e) {
            up = true; // listening but busy
        } catch (IOException | IllegalArgumentException e) {
            up = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            up = false;
        }
        healthy = up;
        healthCheckedAt = now;
        return up;
    }

    public OcrText recognise(Document document, int dpi, int maxPages) {
        JsonNode reply = call(document, dpi, maxPages, null, false, null);
        return new OcrText(reply.path("text").asText(""), reply.path("engine").asText("python"),
                reply.path("meanConfidence").asDouble(0));
    }

    /**
     * OCR of only the given 1-based PDF pages (or the single page of an image when {@code pages} is empty), with
     * line geometry, in the order requested.
     */
    public List<OcrPage> recognisePages(Document document, int dpi, List<Integer> pages) {
        String only = pages.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return pagesOf(call(document, dpi, Math.max(1, Math.min(50, pages.size())), only, true, null), dpi);
    }

    /**
     * OCR of rectangles only: 1-based page -> boxes {x0, y0, x1, y1} in PDF points (top-left origin). Pages come
     * back in ascending page order, boxes in full-page pixels at {@code dpi}.
     */
    public List<OcrPage> recogniseRegions(Document document, int dpi, java.util.SortedMap<Integer, List<double[]>> regions) {
        String json;
        try {
            json = this.json.writeValueAsString(regions);
        } catch (IOException e) {
            throw new ExtractionException("could not encode OCR regions", e);
        }
        return pagesOf(call(document, dpi, 1, null, true, json), dpi);
    }

    private static List<OcrPage> pagesOf(JsonNode reply, int dpi) {
        List<OcrPage> out = new ArrayList<>();
        for (JsonNode p : reply.path("pages")) {
            List<OcrLine> lines = new ArrayList<>();
            for (JsonNode l : p.path("lines")) {
                JsonNode b = l.path("box");
                lines.add(new OcrLine(l.path("text").asText(""), b.path(0).asDouble(), b.path(1).asDouble(),
                        b.path(2).asDouble(), b.path(3).asDouble(), l.path("height").asDouble(),
                        l.path("confidence").asDouble()));
            }
            List<OcrLine> boxes = new ArrayList<>();
            for (JsonNode x : p.path("boxes")) {
                JsonNode b = x.path("box");
                boxes.add(new OcrLine(x.path("text").asText(""), b.path(0).asDouble(), b.path(1).asDouble(),
                        b.path(2).asDouble(), b.path(3).asDouble(), b.path(3).asDouble() - b.path(1).asDouble(),
                        x.path("confidence").asDouble()));
            }
            out.add(new OcrPage(p.path("width").asInt(), p.path("height").asInt(), dpi,
                    p.path("meanConfidence").asDouble(0), List.copyOf(lines), List.copyOf(boxes)));
        }
        return out;
    }

    private JsonNode call(Document document, int dpi, int maxPages, String pages, boolean boxes, String regions) {
        String boundary = "docextract-" + UUID.randomUUID();
        try {
            byte[] body = multipart(boundary, document, dpi, maxPages, pages, boxes, regions);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/ocr"))
                    .timeout(timeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new ExtractionException("OCR service returned HTTP " + response.statusCode() + ": "
                        + abbreviate(response.body()), null);
            }
            JsonNode reply = json.readTree(response.body());
            healthy = true; // a successful call is the best health check
            healthCheckedAt = System.nanoTime();
            return reply;
        } catch (IOException e) {
            healthCheckedAt = Long.MIN_VALUE; // re-check before the next file
            throw new ExtractionException("OCR service call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("interrupted while waiting for the OCR service", e);
        }
    }

    private static byte[] multipart(String boundary, Document document, int dpi, int maxPages, String pages,
                                    boolean boxes, String regions) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(document.bytes().length + 1024);
        String filename = document.filename() == null ? "document" : document.filename().replace("\"", "");
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        out.write(document.bytes());
        write(out, "\r\n");
        field(out, boundary, "dpi", Integer.toString(dpi));
        field(out, boundary, "max_pages", Integer.toString(maxPages));
        if (regions != null) {
            field(out, boundary, "regions", regions);
        }
        if (boxes) {
            field(out, boundary, "include_boxes", "true");
        }
        if (pages != null && !pages.isEmpty()) {
            field(out, boundary, "pages", pages);
        }
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
