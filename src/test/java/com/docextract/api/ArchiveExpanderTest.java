package com.docextract.api;

import com.docextract.service.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveExpanderTest {

    private final ArchiveExpander expander = new ArchiveExpander(500, 20L << 20, 300L << 20);

    @Test
    void replacesAZipWithTheDocumentsInsideInOrder() throws IOException {
        byte[] zip = zip(entries("a.pdf", "%PDF-1.4 a", "sub/b.txt", "hello"));
        List<MultipartFile> out = expander.expand(List.of(
                file("first.txt", "text/plain", "x".getBytes()),
                file("batch.zip", "application/zip", zip)));

        assertThat(out).extracting(MultipartFile::getOriginalFilename)
                .containsExactly("first.txt", "batch.zip/a.pdf", "batch.zip/sub/b.txt");
        assertThat(new String(out.get(2).getBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    void skipsFoldersMacMetadataHiddenAndEmptyEntries() throws IOException {
        Map<String, String> e = entries("docs/", "", "__MACOSX/docs/._a.pdf", "junk", "docs/.DS_Store", "junk",
                "docs/Thumbs.db", "junk", "docs/empty.txt", "", "docs/a.pdf", "%PDF-");
        List<MultipartFile> out = expander.expand(List.of(file("d.zip", null, zip(e))));
        assertThat(out).extracting(MultipartFile::getOriginalFilename).containsExactly("d.zip/docs/a.pdf");
    }

    @Test
    void unpacksNestedArchives() throws IOException {
        byte[] inner = zip(entries("x.txt", "inner"));
        byte[] outer = zipBytes(Map.of("inner.zip", inner));
        List<MultipartFile> out = expander.expand(List.of(file("outer.zip", null, outer)));
        assertThat(out).extracting(MultipartFile::getOriginalFilename).containsExactly("outer.zip/inner.zip/x.txt");
    }

    @Test
    void refusesArchivesNestedTooDeep() throws IOException {
        byte[] z = zip(entries("x.txt", "deep"));
        for (int i = 0; i < 3; i++) {
            z = zipBytes(Map.of("n" + i + ".zip", z));
        }
        byte[] tooDeep = z;
        assertThatThrownBy(() -> expander.expand(List.of(file("top.zip", null, tooDeep))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("nested");
    }

    @Test
    void leavesOfficeFilesAloneEvenThoughTheyAreZips() throws IOException {
        byte[] docxLike = zip(entries("word/document.xml", "<w:document/>"));
        List<MultipartFile> out = expander.expand(List.of(file("report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docxLike)));
        assertThat(out).extracting(MultipartFile::getOriginalFilename).containsExactly("report.docx");
    }

    @Test
    void passesThroughAFileNamedZipThatIsNotOne() {
        List<MultipartFile> out = expander.expand(List.of(file("fake.zip", null, "not a zip".getBytes())));
        assertThat(out).extracting(MultipartFile::getOriginalFilename).containsExactly("fake.zip");
    }

    @Test
    void capsTheNumberOfDocuments() throws IOException {
        ArchiveExpander small = new ArchiveExpander(3, 20L << 20, 300L << 20);
        byte[] zip = zip(entries("1.txt", "a", "2.txt", "b", "3.txt", "c", "4.txt", "d"));
        assertThatThrownBy(() -> small.expand(List.of(file("many.zip", null, zip))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("at most 3 documents");
    }

    @Test
    void stopsAZipBombOnTheBytesActuallyInflated() throws IOException {
        ArchiveExpander small = new ArchiveExpander(500, 1L << 20, 2L << 20);
        byte[] zeros = new byte[900 * 1024]; // compresses to ~1 KB each
        Map<String, byte[]> e = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            e.put("z" + i + ".bin", zeros);
        }
        byte[] bomb = zipBytes(e);
        assertThat(bomb.length).isLessThan(20_000);
        assertThatThrownBy(() -> small.expand(List.of(file("bomb.zip", null, bomb))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("in total");
    }

    @Test
    void refusesAnOversizedEntryAndAnOversizedPlainUpload() throws IOException {
        ArchiveExpander small = new ArchiveExpander(500, 1024, 300L << 20);
        byte[] zip = zipBytes(Map.of("big.txt", new byte[2048]));
        assertThatThrownBy(() -> small.expand(List.of(file("a.zip", null, zip))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("a.zip/big.txt");
        assertThatThrownBy(() -> small.expand(List.of(file("big.pdf", null, new byte[2048]))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("big.pdf");
    }

    private static MockMultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("files", name, type, bytes);
    }

    private static Map<String, String> entries(String... nameThenContent) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameThenContent.length; i += 2) {
            m.put(nameThenContent[i], nameThenContent[i + 1]);
        }
        return m;
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        Map<String, byte[]> b = new LinkedHashMap<>();
        entries.forEach((k, v) -> b.put(k, v.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(b);
    }

    private static byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
