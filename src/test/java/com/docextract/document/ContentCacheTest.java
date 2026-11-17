package com.docextract.document;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ContentCacheTest {

    @Test
    void sameBytesAreReadOnceEvenAsANewUpload() {
        ContentCache cache = new ContentCache(10);
        AtomicInteger reads = new AtomicInteger();
        Document first = new Document("a.pdf", null, "same content".getBytes());
        Document reupload = new Document("renamed.pdf", null, "same content".getBytes());
        assertThat(cache.<String>get(first, "ocr", () -> "text" + reads.incrementAndGet())).isEqualTo("text1");
        assertThat(cache.<String>get(reupload, "ocr", () -> "text" + reads.incrementAndGet())).isEqualTo("text1");
        assertThat(cache.<String>peek(reupload, "ocr")).contains("text1");
        assertThat(cache.<String>peek(reupload, "other-purpose")).isEmpty();
        assertThat(reads).hasValue(1);
    }

    @Test
    void leastRecentlyUsedEntryIsEvictedAndZeroDisables() {
        ContentCache cache = new ContentCache(2);
        Document a = new Document("a", null, new byte[]{1});
        Document b = new Document("b", null, new byte[]{2});
        Document c = new Document("c", null, new byte[]{3});
        cache.get(a, "t", () -> "A");
        cache.get(b, "t", () -> "B");
        cache.get(a, "t", () -> "A");   // a is now the most recent
        cache.get(c, "t", () -> "C");   // evicts b
        assertThat(cache.<String>peek(a, "t")).contains("A");
        assertThat(cache.<String>peek(b, "t")).isEmpty();

        ContentCache off = new ContentCache(0);
        AtomicInteger reads = new AtomicInteger();
        off.get(a, "t", reads::incrementAndGet);
        off.get(a, "t", reads::incrementAndGet);
        assertThat(reads).hasValue(2);
    }
}
