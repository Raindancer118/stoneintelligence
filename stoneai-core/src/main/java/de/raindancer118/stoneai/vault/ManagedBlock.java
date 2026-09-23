package de.raindancer118.stoneai.vault;

import java.util.ArrayList;
import java.util.List;

/**
 * The mechanism behind "StoneAI never overwrites anything you wrote".
 *
 * <p>Generated text lives between {@code <!-- stoneai:begin id=… -->} and the matching end
 * marker. Writing means: replace the block with this id if it exists, otherwise append a new one.
 * Everything outside the markers is copied through byte for byte, so a note can be freely edited
 * by hand and still be updated by a later run — and a re-run of the same document changes exactly
 * its own block instead of duplicating it.
 *
 * <p>Markers inside fenced code are ignored, so a note that documents this very format does not
 * accidentally become a target.
 */
public final class ManagedBlock {

    private static final String BEGIN = "<!-- stoneai:begin id=";
    private static final String END = "<!-- stoneai:end id=";
    private static final String MARKER_SUFFIX = " -->";

    private ManagedBlock() {
    }

    public static String beginMarker(String id) {
        return BEGIN + id + MARKER_SUFFIX;
    }

    public static String endMarker(String id) {
        return END + id + MARKER_SUFFIX;
    }

    /** Adds or replaces the block with {@code id}, leaving all other content untouched. */
    public static String apply(String content, String id, String blockContent) {
        String block = beginMarker(id) + "\n" + blockContent.strip() + "\n" + endMarker(id);
        Span span = find(content, id);
        if (span == null) {
            String base = content.isBlank() ? "" : content.stripTrailing() + "\n\n";
            return base + block + "\n";
        }
        String separator = span.start() > 0 ? "\n\n" : "";
        return content.substring(0, span.start()) + separator + block + content.substring(span.end());
    }

    /** The note without any managed block — what a human would see as "their" content. */
    public static String strip(String content) {
        String result = content;
        for (String id : ids(content)) {
            Span span = find(result, id);
            if (span == null) {
                continue;
            }
            String before = result.substring(0, span.start());
            String after = result.substring(span.end());
            result = before.stripTrailing() + (after.isBlank() ? "" : after);
            if (!before.isBlank() && !result.endsWith("\n")) {
                result = result + "\n";
            }
        }
        return result;
    }

    public static boolean contains(String content, String id) {
        return find(content, id) != null;
    }

    /** The ids of all managed blocks, in the order they appear. */
    public static List<String> ids(String content) {
        List<String> ids = new ArrayList<>();
        for (String line : outsideCode(content)) {
            String trimmed = line.strip();
            if (trimmed.startsWith(BEGIN) && trimmed.endsWith(MARKER_SUFFIX)) {
                ids.add(trimmed.substring(BEGIN.length(), trimmed.length() - MARKER_SUFFIX.length()));
            }
        }
        return ids;
    }

    /** The character range of a block including its markers and the blank line before it. */
    private static Span find(String content, String id) {
        String begin = beginMarker(id);
        String end = endMarker(id);
        if (!ids(content).contains(id)) {
            return null;
        }
        int start = content.indexOf(begin);
        int endIndex = content.indexOf(end, start);
        if (start < 0 || endIndex < 0) {
            return null;
        }
        int endOfBlock = endIndex + end.length();
        // Swallow the blank lines in front of the block, so replacing it cannot pile up
        // empty lines; the writer puts a single blank line back.
        while (start > 0 && content.charAt(start - 1) == '\n') {
            start--;
        }
        return new Span(start, endOfBlock);
    }

    /** The lines of {@code content} that are not inside a fenced code block. */
    private static List<String> outsideCode(String content) {
        List<String> lines = new ArrayList<>();
        boolean inFence = false;
        for (String line : content.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (!inFence) {
                lines.add(line);
            }
        }
        return lines;
    }

    private record Span(int start, int end) {
    }
}
