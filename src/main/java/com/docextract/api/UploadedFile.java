package com.docextract.api;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/** A document held in memory, such as one entry unpacked from an uploaded ZIP; handled exactly like an upload. */
record UploadedFile(String name, String contentType, byte[] bytes) implements MultipartFile {

    @Override public String getName() { return "files"; }
    @Override public String getOriginalFilename() { return name; }
    @Override public String getContentType() { return contentType; }
    @Override public boolean isEmpty() { return bytes.length == 0; }
    @Override public long getSize() { return bytes.length; }
    @Override public byte[] getBytes() { return bytes; }
    @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }

    @Override
    public void transferTo(File dest) throws IOException {
        Files.write(dest.toPath(), bytes);
    }
}
