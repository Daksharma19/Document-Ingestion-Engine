package com.docextract.api;

import com.docextract.service.InvalidRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Replaces each uploaded {@code .zip} with the documents inside it, so a whole folder of documents can be sent as one
 * file. Entries are named {@code archive.zip/path/inside.pdf}. Everything stays in memory and nothing is written to
 * disk, so entry paths are only labels. Zip bombs are bounded by counting the bytes actually inflated (headers are not
 * trusted) against per-entry, per-request and entry-count limits; nested archives are unpacked to a fixed depth.
 * Office files (DOCX, XLSX, ...) are ZIPs internally and are left alone: only real {@code .zip} uploads are unpacked.
 */
@Component
public class ArchiveExpander {

    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final int MAX_DEPTH = 3;
    private static final Charset LEGACY_NAMES = Charset.forName("IBM437");

    private final int maxDocuments;
    private final long maxEntryBytes;
    private final long maxTotalBytes;

    public ArchiveExpander(@Value("${docextract.archive.max-documents:500}") int maxDocuments,
                           @Value("${docextract.archive.max-entry-bytes:20971520}") long maxEntryBytes,
                           @Value("${docextract.archive.max-total-bytes:314572800}") long maxTotalBytes) {
        this.maxDocuments = maxDocuments;
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    public int maxDocuments() {
        return maxDocuments;
    }

    /**
     * The uploads with every archive replaced by its documents, in upload order then archive order. A file that
     * claims to be a ZIP but is not one is passed through unchanged (it then fails per file, like any unreadable
     * upload). Exceeding a limit is a request-level 400.
     */
    public List<MultipartFile> expand(List<MultipartFile> uploads) {
        Budget budget = new Budget();
        List<MultipartFile> out = new ArrayList<>();
        for (MultipartFile upload : uploads) {
            String name = upload.getOriginalFilename() == null ? "upload" : upload.getOriginalFilename();
            if (!isArchive(name, upload.getContentType())) {
                if (upload.getSize() > maxEntryBytes) {
                    throw new InvalidRequestException(name + " is over " + mb(maxEntryBytes) + " (only .zip archives may be larger)");
                }
                add(out, upload);
                continue;
            }
            byte[] bytes;
            try {
                bytes = upload.getBytes();
            } catch (IOException e) {
                throw new InvalidRequestException("could not read uploaded file " + name);
            }
            if (!startsWithZipMagic(bytes)) {
                add(out, upload); // not really a ZIP: let the normal pipeline report it per file
                continue;
            }
            unpack(name, bytes, 1, budget, out);
        }
        return out;
    }

    private void unpack(String archiveName, byte[] zip, int depth, Budget budget, List<MultipartFile> out) {
        // Entries flagged UTF-8 are decoded as UTF-8 regardless; the rest are legacy (CP437) names, which never fail to decode.
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), LEGACY_NAMES)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String path = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || isJunk(path)) {
                    continue;
                }
                String label = archiveName + "/" + path;
                byte[] content = readBounded(in, label, budget);
                if (content.length == 0) {
                    continue;
                }
                if (isArchive(path, null) && startsWithZipMagic(content)) {
                    if (depth >= MAX_DEPTH) {
                        throw new InvalidRequestException(label + ": archives nested more than " + MAX_DEPTH + " deep");
                    }
                    unpack(label, content, depth + 1, budget, out);
                    continue;
                }
                add(out, new UploadedFile(label, null, content));
            }
        } catch (ZipException e) {
            throw new InvalidRequestException(archiveName + " is not a readable ZIP archive: " + e.getMessage());
        } catch (IOException e) {
            throw new InvalidRequestException("could not read archive " + archiveName + ": " + e.getMessage());
        }
    }

    private byte[] readBounded(InputStream in, String label, Budget budget) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) {
            if (buf.size() + n > maxEntryBytes) {
                throw new InvalidRequestException(label + " is over " + mb(maxEntryBytes) + " unpacked");
            }
            budget.consume(n);
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private void add(List<MultipartFile> out, MultipartFile file) {
        if (out.size() >= maxDocuments) {
            throw new InvalidRequestException("at most " + maxDocuments + " documents per request (after unpacking archives)");
        }
        out.add(file);
    }

    static boolean isArchive(String name, String contentType) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".zip")) {
            return true;
        }
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("zip")) {
            return false;
        }
        // a zip content type on a file whose name says nothing else (e.g. no extension)
        return !n.matches(".*\\.(docx|docm|xlsx|xlsm|pptx|pptm|odt|ods|odp|epub|jar)$");
    }

    private static boolean isJunk(String path) {
        if (path.startsWith("__MACOSX/") || path.contains("/__MACOSX/")) {
            return true;
        }
        String base = path.substring(path.lastIndexOf('/') + 1);
        return base.startsWith(".") || base.equalsIgnoreCase("Thumbs.db") || base.equalsIgnoreCase("desktop.ini");
    }

    private static boolean startsWithZipMagic(byte[] b) {
        return b.length >= 4 && b[0] == ZIP_MAGIC[0] && b[1] == ZIP_MAGIC[1] && b[2] == ZIP_MAGIC[2] && b[3] == ZIP_MAGIC[3];
    }

    private static String mb(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }

    /** Uncompressed bytes inflated so far in this request, across every archive in it. */
    private final class Budget {
        private long used;

        void consume(long n) {
            used += n;
            if (used > maxTotalBytes) {
                throw new InvalidRequestException("archives unpack to more than " + mb(maxTotalBytes) + " in total");
            }
        }
    }
}
