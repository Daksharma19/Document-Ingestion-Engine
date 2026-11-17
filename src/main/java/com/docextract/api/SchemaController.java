package com.docextract.api;

import com.docextract.schema.SchemaCatalog;
import com.docextract.schema.SchemaCatalog.SchemaPreset;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** The built-in schemas, for clients that want a starting point (the frontend's preset picker). */
@RestController
public class SchemaController {

    private final SchemaCatalog catalog;

    public SchemaController(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/schemas")
    public List<SchemaPreset> list() {
        return catalog.all();
    }

    @GetMapping("/api/schemas/{id}")
    public SchemaPreset get(@PathVariable String id) {
        return catalog.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no built-in schema '" + id + "'"));
    }
}
