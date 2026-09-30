package com.springaimcpservercommon.ai.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document into chunks at paragraph boundaries, keeping the Markdown heading trail in front of every
 * chunk so a chunk still says what it is about. Deterministic: the same text gives the same chunks, so the index
 * file only changes when the documents do.
 */
public final class TextChunker {

    private final int maxChars;

    /**
     * Creates a chunker.
     *
     * @param maxChars largest chunk size in characters, 200..20000
     */
    public TextChunker(int maxChars) {
        if (maxChars < 200 || maxChars > 20_000) {
            throw new IllegalArgumentException("maxChars must be 200..20000");
        }
        this.maxChars = maxChars;
    }

    /**
     * Splits a document.
     *
     * @param text the document
     * @return chunk texts in document order; empty for a blank document
     */
    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        String heading = "";
        StringBuilder current = new StringBuilder();
        String[] paragraphs = text.replace("\r\n", "\n").split("\n\\s*\n");
        for (String raw : paragraphs) {
            String paragraph = raw.strip();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (paragraph.startsWith("#")) {
                flush(chunks, current);
                int newline = paragraph.indexOf('\n');
                heading = (newline < 0 ? paragraph : paragraph.substring(0, newline)).strip();
                paragraph = newline < 0 ? "" : paragraph.substring(newline + 1).strip();
                if (paragraph.isEmpty()) {
                    continue;
                }
            }
            String prefix = heading.isEmpty() ? "" : heading + "\n";
            for (String part : split(paragraph, Math.max(100, maxChars - prefix.length()))) {
                if (current.length() > 0 && current.length() + part.length() + 2 > maxChars - prefix.length()) {
                    flush(chunks, current);
                }
                if (current.length() == 0) {
                    current.append(prefix);
                } else {
                    current.append("\n\n");
                }
                current.append(part);
            }
        }
        flush(chunks, current);
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        String value = current.toString().strip();
        if (!value.isEmpty()) {
            chunks.add(value);
        }
        current.setLength(0);
    }

    private static List<String> split(String paragraph, int limit) {
        List<String> parts = new ArrayList<>();
        String rest = paragraph;
        while (rest.length() > limit) {
            int cut = rest.lastIndexOf(". ", limit);
            if (cut < limit / 2) {
                cut = rest.lastIndexOf(' ', limit);
            }
            cut = cut < limit / 2 ? limit : cut + 1;
            parts.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            parts.add(rest);
        }
        return parts;
    }
}
