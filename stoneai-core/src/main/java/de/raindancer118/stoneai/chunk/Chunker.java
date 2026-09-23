package de.raindancer118.stoneai.chunk;

import de.raindancer118.stoneai.source.DocumentKind;
import de.raindancer118.stoneai.source.Page;
import de.raindancer118.stoneai.source.SourceDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts a document into pieces a model can work on. The cuts follow the document's own structure —
 * Markdown headings, or page boundaries for PDFs — rather than a fixed character count, because a
 * chunk that spans two topics produces notes that mix them.
 *
 * <p>Sections longer than the budget are split further, with an overlap so a definition that
 * straddles a cut is still complete in one of the pieces. Fenced code blocks are never cut.
 */
public final class Chunker {

    /**
     * Below this many non-whitespace characters a piece is a leftover — a lone heading, a page
     * number, an overlap remnant — not something to extract knowledge from. Kept deliberately low:
     * silently dropping a short but real page would lose content without telling anyone.
     */
    private static final int MINIMUM_USEFUL_CHARS = 4;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$", Pattern.MULTILINE);

    private final int maxChars;
    private final int overlapChars;

    public Chunker(int maxChars, int overlapChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            throw new IllegalArgumentException("overlapChars must be between 0 and maxChars");
        }
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    public List<Chunk> split(SourceDocument document) {
        List<Chunk> chunks = new ArrayList<>();
        if (document.kind() == DocumentKind.MARKDOWN) {
            for (Section section : sections(document.fullText())) {
                addPieces(chunks, section.body(),
                        new Provenance(document.title(), document.file(), null, section.headingPath()));
            }
        } else {
            packPages(chunks, document);
        }
        return chunks;
    }

    /**
     * Consecutive pages share a chunk up to the budget, each marked with {@code [S. n]}; a chunk
     * only ever ends at a page boundary, unless one page alone exceeds the budget. A slide deck
     * thus becomes a handful of calls with context instead of one call per slide.
     */
    private void packPages(List<Chunk> chunks, SourceDocument document) {
        StringBuilder packed = new StringBuilder();
        Integer first = null;
        Integer last = null;
        for (Page page : document.pages()) {
            String text = page.text().strip();
            if (visibleChars(text) < MINIMUM_USEFUL_CHARS) {
                continue;
            }
            String marked = "[S. " + page.number() + "]\n" + text;
            if (marked.length() > maxChars) {
                flush(chunks, document, packed, first, last);
                packed.setLength(0);
                first = null;
                addPieces(chunks, text, new Provenance(document.title(), document.file(), page.number(), null));
                continue;
            }
            if (packed.length() > 0 && packed.length() + 2 + marked.length() > maxChars) {
                flush(chunks, document, packed, first, last);
                packed.setLength(0);
                first = null;
            }
            if (packed.length() > 0) {
                packed.append("\n\n");
            }
            packed.append(marked);
            first = first == null ? page.number() : first;
            last = page.number();
        }
        flush(chunks, document, packed, first, last);
    }

    private static void flush(List<Chunk> chunks, SourceDocument document, StringBuilder packed, Integer first, Integer last) {
        if (packed.length() == 0 || first == null) {
            return;
        }
        chunks.add(new Chunk(chunks.size(), packed.toString(),
                new Provenance(document.title(), document.file(), first, null, last.equals(first) ? null : last)));
    }

    private void addPieces(List<Chunk> chunks, String text, Provenance provenance) {
        for (String piece : cut(text)) {
            if (visibleChars(piece) < MINIMUM_USEFUL_CHARS) {
                continue;
            }
            chunks.add(new Chunk(chunks.size(), piece.strip(), provenance));
        }
    }

    /** Splits one section into budget-sized pieces, preferring paragraph boundaries. */
    private List<String> cut(String text) {
        String body = text.strip();
        if (body.isEmpty()) {
            return List.of();
        }
        if (body.length() <= maxChars) {
            return List.of(body);
        }

        List<String> units = new ArrayList<>();
        for (String block : blocks(body)) {
            if (block.startsWith("```") || block.length() <= maxChars) {
                units.add(block);
            } else {
                units.addAll(splitOversized(block));
            }
        }

        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String block : units) {
            if (current.length() > 0 && current.length() + block.length() + 2 > maxChars) {
                pieces.add(current.toString().strip());
                current = new StringBuilder(overlapOf(current.toString()));
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(block);
        }
        if (!current.toString().isBlank()) {
            pieces.add(current.toString().strip());
        }
        return pieces;
    }

    /**
     * Splits a paragraph that is longer than the whole budget. Sentences first, and only if a
     * single sentence is still too long, words — so a cut lands between thoughts wherever
     * possible instead of mid-clause.
     */
    private List<String> splitOversized(String block) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : block.split("(?<=[.!?:;])\\s+")) {
            for (String unit : sentence.length() <= maxChars ? List.of(sentence) : splitWords(sentence)) {
                if (current.length() > 0 && current.length() + unit.length() + 1 > maxChars) {
                    pieces.add(current.toString().strip());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(unit);
            }
        }
        if (!current.toString().isBlank()) {
            pieces.add(current.toString().strip());
        }
        return pieces;
    }

    private List<String> splitWords(String sentence) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : sentence.split("\\s+")) {
            if (current.length() > 0 && current.length() + word.length() + 1 > maxChars) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    /**
     * Paragraph-sized blocks, with fenced code kept whole. A code fence split across two chunks
     * loses its language and its closing marker, and models then treat the halves as prose.
     */
    private static List<String> blocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder fence = null;
        StringBuilder paragraph = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                if (fence == null) {
                    flush(blocks, paragraph);
                    fence = new StringBuilder(line);
                } else {
                    fence.append('\n').append(line);
                    blocks.add(fence.toString());
                    fence = null;
                }
                continue;
            }
            if (fence != null) {
                fence.append('\n').append(line);
                continue;
            }
            if (line.isBlank()) {
                flush(blocks, paragraph);
            } else {
                if (paragraph.length() > 0) {
                    paragraph.append('\n');
                }
                paragraph.append(line);
            }
        }
        if (fence != null) {
            blocks.add(fence.toString());
        }
        flush(blocks, paragraph);
        return blocks;
    }

    private static void flush(List<String> blocks, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            blocks.add(paragraph.toString());
            paragraph.setLength(0);
        }
    }

    /** The tail of a piece that is repeated at the head of the next one. */
    private String overlapOf(String piece) {
        if (overlapChars == 0 || piece.length() <= overlapChars) {
            return "";
        }
        String tail = piece.substring(piece.length() - overlapChars);
        int boundary = tail.indexOf(' ');
        return boundary > 0 ? tail.substring(boundary + 1) : tail;
    }

    /** Splits Markdown into sections, remembering the chain of headings above each one. */
    private static List<Section> sections(String markdown) {
        List<Section> sections = new ArrayList<>();
        Matcher matcher = HEADING.matcher(markdown);
        String[] trail = new String[7];
        int previousEnd = 0;
        String currentPath = "";

        while (matcher.find()) {
            String body = markdown.substring(previousEnd, matcher.start());
            if (!body.isBlank()) {
                sections.add(new Section(currentPath, body));
            }
            int level = matcher.group(1).length();
            trail[level] = matcher.group(2).trim();
            for (int deeper = level + 1; deeper < trail.length; deeper++) {
                trail[deeper] = null;
            }
            currentPath = pathOf(trail);
            previousEnd = matcher.end();
        }
        String rest = markdown.substring(previousEnd);
        if (!rest.isBlank()) {
            sections.add(new Section(currentPath, rest));
        }
        return sections;
    }

    private static String pathOf(String[] trail) {
        StringBuilder path = new StringBuilder();
        for (String heading : trail) {
            if (heading == null) {
                continue;
            }
            if (path.length() > 0) {
                path.append(" > ");
            }
            path.append(heading);
        }
        return path.toString();
    }

    private static int visibleChars(String text) {
        return (int) text.chars().filter(c -> !Character.isWhitespace(c)).count();
    }

    private record Section(String headingPath, String body) {
    }
}
