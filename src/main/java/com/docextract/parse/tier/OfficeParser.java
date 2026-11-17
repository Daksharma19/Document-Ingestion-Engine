package com.docextract.parse.tier;

import com.docextract.document.Document;
import com.docextract.parse.model.Block;
import com.docextract.parse.model.PageMethod;
import com.docextract.strategy.ExtractionException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DOCX, XLSX, PPTX, HTML, ODT, text...: these files already carry their structure, so no layout guessing is needed.
 * Tika's XHTML output (h1..h6, p, li, table/tr/td) maps straight onto blocks.
 */
@Component
public class OfficeParser {

    private final AutoDetectParser parser = new AutoDetectParser();

    public List<Block> parse(Document document) {
        BlockHandler handler = new BlockHandler();
        try {
            Metadata metadata = new Metadata();
            if (document.filename() != null) {
                metadata.set("resourceName", document.filename());
            }
            parser.parse(new ByteArrayInputStream(document.bytes()), handler, metadata, new ParseContext());
        } catch (Exception e) {
            throw new ExtractionException("could not read " + document.filename(), e);
        }
        return List.copyOf(handler.blocks);
    }

    /** Names of embedded files Tika writes into the text ("image1.png"): not content. */
    static final java.util.regex.Pattern EMBEDDED_FILE = java.util.regex.Pattern.compile(
            "(?i)(image|picture|chart|object|oleobject)\\d*\\.(png|jpe?g|gif|bmp|emf|wmf|tiff?|bin|xml)");

    static final class BlockHandler extends DefaultHandler {
        final List<Block> blocks = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private String element;
        private int heading;
        private int tableDepth;
        private List<List<String>> rows;
        private List<String> row;
        private StringBuilder cell;
        private int colspan = 1;

        @Override
        public void startElement(String uri, String local, String qName, Attributes atts) {
            String n = local.isEmpty() ? qName : local;
            switch (n) {
                case "table" -> {
                    if (tableDepth++ == 0) {
                        flush();
                        rows = new ArrayList<>();
                    }
                }
                case "tr" -> {
                    if (tableDepth == 1) {
                        row = new ArrayList<>();
                    }
                }
                case "td", "th" -> {
                    if (tableDepth == 1) {
                        cell = new StringBuilder();
                        try {
                            String span = atts.getValue("colspan");
                            colspan = span == null ? 1 : Math.max(1, Math.min(50, Integer.parseInt(span.trim())));
                        } catch (NumberFormatException e) {
                            colspan = 1;
                        }
                    }
                }
                case "h1", "h2", "h3", "h4", "h5", "h6", "p", "li" -> {
                    if (tableDepth == 0) {
                        flush();
                        element = n;
                        heading = n.startsWith("h") ? n.charAt(1) - '0' : 0;
                    } else if (cell != null && !cell.isEmpty()) {
                        cell.append(' ');
                    }
                }
                case "br" -> append(" ");
                default -> { }
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) {
            String n = local.isEmpty() ? qName : local;
            switch (n) {
                case "table" -> {
                    if (--tableDepth == 0) {
                        List<List<String>> nonEmpty = rows.stream()
                                .filter(r -> r.stream().anyMatch(c -> !c.isBlank())).toList();
                        if (!nonEmpty.isEmpty()) {
                            int max = nonEmpty.stream().mapToInt(List::size).max().orElse(0);
                            List<List<String>> padded = new ArrayList<>();
                            for (List<String> r : nonEmpty) {
                                List<String> p = new ArrayList<>(r);
                                while (p.size() < max) {
                                    p.add("");
                                }
                                padded.add(List.copyOf(p));
                            }
                            blocks.add(Block.table(List.copyOf(padded), null, PageMethod.NATIVE));
                        }
                        rows = null;
                    }
                }
                case "tr" -> {
                    if (tableDepth == 1 && row != null) {
                        rows.add(List.copyOf(row));
                        row = null;
                    }
                }
                case "td", "th" -> {
                    if (tableDepth == 1 && cell != null && row != null) {
                        row.add(cell.toString().replaceAll("\\s+", " ").strip());
                        // a merged cell keeps the columns after it in place
                        for (int k = 1; k < colspan; k++) {
                            row.add("");
                        }
                        cell = null;
                        colspan = 1;
                    }
                }
                case "h1", "h2", "h3", "h4", "h5", "h6", "p", "li" -> {
                    if (tableDepth == 0) {
                        flush();
                    }
                }
                default -> { }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            append(new String(ch, start, length));
        }

        private void append(String s) {
            if (tableDepth > 0) {
                if (cell != null) {
                    cell.append(s);
                }
            } else {
                text.append(s);
            }
        }

        private void flush() {
            String t = text.toString().replaceAll("\\s+", " ").strip();
            text.setLength(0);
            if (!t.isEmpty() && !EMBEDDED_FILE.matcher(t).matches()) {
                java.util.regex.Matcher bullet = NativePageParser.BULLET.matcher(t);
                if (heading == 0 && !"li".equals(element) && bullet.find() && bullet.end() < t.length()) {
                    // Word lists arrive as <p> carrying their bullet or number ("· Paddle about", "1. Wear ...")
                    blocks.add(Block.listItem(t.substring(bullet.end()).strip(), null, PageMethod.NATIVE));
                } else if (heading > 0) {
                    blocks.add(Block.heading(t, heading, null, PageMethod.NATIVE));
                } else if ("li".equals(element)) {
                    blocks.add(Block.listItem(t, null, PageMethod.NATIVE));
                } else {
                    // plain-text files arrive as one <p> with line breaks already collapsed; split on bullets later if needed
                    blocks.add(Block.paragraph(t, null, PageMethod.NATIVE));
                }
            }
            element = null;
            heading = 0;
        }

        @Override
        public void endDocument() {
            flush();
        }
    }
}
