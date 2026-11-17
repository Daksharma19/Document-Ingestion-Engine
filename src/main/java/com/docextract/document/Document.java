package com.docextract.document;

import java.util.Locale;

public record Document(String filename, String contentType, byte[] bytes) {

    public boolean isPdf() {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("pdf")) {
            return true;
        }
        if (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return true;
        }
        return bytes.length >= 5 && new String(bytes, 0, 5).equals("%PDF-");
    }
}
