package com.docextract.parse;

import com.docextract.parse.model.Block;
import com.docextract.parse.model.ParsedPage;

import java.util.List;

/** Blocks to GitHub-flavoured Markdown; pages after the first are marked with an HTML comment. */
public final class MarkdownWriter {

    private MarkdownWriter() {
    }

    public static String write(List<ParsedPage> pages) {
        StringBuilder md = new StringBuilder();
        for (ParsedPage page : pages) {
            if (pages.size() > 1) {
                md.append("<!-- page ").append(page.index()).append(" -->\n\n");
            }
            Block prev = null;
            for (Block b : page.blocks()) {
                if (prev != null) {
                    // consecutive list items form one list
                    md.append(prev.type() == b.type() && b.type() == com.docextract.parse.model.BlockType.LIST_ITEM ? "\n" : "\n\n");
                }
                md.append(block(b));
                prev = b;
            }
            if (!page.blocks().isEmpty()) {
                md.append("\n\n");
            }
        }
        return md.toString().strip() + "\n";
    }

    static String block(Block b) {
        return switch (b.type()) {
            case HEADING -> "#".repeat(Math.max(1, Math.min(6, b.level() == null ? 2 : b.level()))) + " " + b.text();
            case LIST_ITEM -> "- " + b.text();
            case PARAGRAPH -> b.text();
            case TABLE -> table(b.rows());
            case FIGURE -> "> **[Figure text]** " + b.text().replace("\n", " · ");
        };
    }

    static String table(List<List<String>> rows) {
        StringBuilder t = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            t.append("| ").append(String.join(" | ", rows.get(r).stream().map(MarkdownWriter::cell).toList())).append(" |");
            if (r == 0) {
                t.append("\n|").append(" --- |".repeat(rows.get(0).size()));
            }
            if (r < rows.size() - 1) {
                t.append('\n');
            }
        }
        return t.toString();
    }

    private static String cell(String c) {
        return c.replace("|", "\\|");
    }
}
