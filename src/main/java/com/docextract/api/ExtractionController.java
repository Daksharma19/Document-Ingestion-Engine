package com.docextract.api;

import com.docextract.service.ExtractionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class ExtractionController {

    private final ExtractionService extractionService;

    public ExtractionController(ExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    /**
     * Multipart: one or more {@code files} parts (a single legacy {@code file} part is also accepted), one
     * {@code schema} (JSON Schema text), optional {@code rules} (JSON array of rule expressions) and optional
     * {@code tier} (auto | native-text | ocr | vision-llm) and {@code includeText} (echo the text the tier read). Returns one result per file, in upload order.
     */
    @PostMapping(path = "/api/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<FileExtractionResult> extract(@RequestParam(value = "files", required = false) List<MultipartFile> files,
                                              @RequestParam(value = "file", required = false) List<MultipartFile> file,
                                              @RequestParam("schema") String schema,
                                              @RequestParam(value = "rules", required = false) String rules,
                                              @RequestParam(value = "tier", required = false, defaultValue = "auto") String tier,
                                              @RequestParam(value = "includeText", required = false, defaultValue = "false") boolean includeText) {
        List<MultipartFile> all = new ArrayList<>();
        if (files != null) {
            all.addAll(files);
        }
        if (file != null) {
            all.addAll(file);
        }
        return extractionService.extract(all, schema, rules, tier, includeText);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
