package com.docextract.api;

import com.docextract.document.Document;
import com.docextract.parse.ParseEngine;
import com.docextract.parse.model.ParsedDocument;
import com.docextract.service.InvalidRequestException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** POST /api/parse: one structured parse (pages, blocks, Markdown) per uploaded file, in upload order. */
@RestController
public class ParseController {

    static final int MAX_FILES = 50;

    private final ParseEngine engine;
    private final ExecutorService pool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()), r -> {
                Thread t = new Thread(r, "parse");
                t.setDaemon(true);
                return t;
            });

    public ParseController(ParseEngine engine) {
        this.engine = engine;
    }

    @PostMapping(path = "/api/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<ParsedDocument> parse(@RequestParam(value = "files", required = false) List<MultipartFile> files,
                                      @RequestParam(value = "includeMarkdown", defaultValue = "true") boolean includeMarkdown,
                                      @RequestParam(value = "figures", defaultValue = "true") boolean figures) {
        if (files == null || files.isEmpty()) {
            throw new InvalidRequestException("at least one file is required (multipart part 'files')");
        }
        if (files.size() > MAX_FILES) {
            throw new InvalidRequestException("at most " + MAX_FILES + " files per request");
        }
        List<CompletableFuture<ParsedDocument>> futures = new ArrayList<>();
        for (MultipartFile f : files) {
            ParseEngine.Options options = new ParseEngine.Options(figures);
            futures.add(CompletableFuture.supplyAsync(() -> parseOne(f, includeMarkdown, options), pool));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private ParsedDocument parseOne(MultipartFile file, boolean includeMarkdown, ParseEngine.Options options) {
        String name = file.getOriginalFilename();
        if (file.isEmpty()) {
            return ParsedDocument.error(name, null, null, "file is empty");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return ParsedDocument.error(name, null, null, "could not read uploaded file");
        }
        ParsedDocument d;
        try {
            d = engine.parse(new Document(name, file.getContentType(), bytes), options);
        } catch (RuntimeException e) {
            // one bad file never fails the batch
            org.slf4j.LoggerFactory.getLogger(ParseController.class).error("parse crashed on {}", name, e);
            return ParsedDocument.error(name, null, null, "internal error: " + e);
        }
        return includeMarkdown ? d : new ParsedDocument(d.filename(), d.mediaType(), d.kind(), d.status(), d.error(),
                d.pages(), null, d.costUnits(), d.elapsedMs());
    }
}
