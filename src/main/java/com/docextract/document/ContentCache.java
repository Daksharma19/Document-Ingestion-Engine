package com.docextract.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Remembers what was read from a document, keyed by a hash of its bytes: the text layer, the OCR text. Reading is
 * the expensive part of extraction (OCR: seconds per page), while mapping onto a schema takes milliseconds, so the
 * same file uploaded again (a new schema, another tier, the auto-router's second look) is answered from memory.
 * A small LRU; {@code docextract.cache.max-entries=0} disables it.
 */
@Component
public class ContentCache {

    private final int maxEntries;
    private final Map<String, Object> entries;
    /** The digest of a byte array is computed once per request, however many lookups use it. */
    private final Map<byte[], String> digests = Collections.synchronizedMap(new WeakHashMap<>());

    public ContentCache(@Value("${docextract.cache.max-entries:256}") int maxEntries) {
        this.maxEntries = maxEntries;
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                return size() > ContentCache.this.maxEntries;
            }
        });
    }

    /**
     * The cached value for this document and purpose, computing it on a miss. Concurrent misses for the same key
     * may both compute; the value is the same either way, and no lock is held while reading a document.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Document document, String purpose, Supplier<T> compute) {
        if (maxEntries <= 0) {
            return compute.get();
        }
        String key = digest(document.bytes()) + "|" + purpose;
        Object hit = entries.get(key);
        if (hit != null) {
            return (T) hit;
        }
        T value = compute.get();
        if (value != null) {
            entries.put(key, value);
        }
        return value;
    }

    /** The cached value, without computing it on a miss. */
    @SuppressWarnings("unchecked")
    public <T> java.util.Optional<T> peek(Document document, String purpose) {
        return maxEntries <= 0 ? java.util.Optional.empty()
                : java.util.Optional.ofNullable((T) entries.get(digest(document.bytes()) + "|" + purpose));
    }

    public void clear() {
        entries.clear();
    }

    private String digest(byte[] bytes) {
        return digests.computeIfAbsent(bytes, b -> {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
