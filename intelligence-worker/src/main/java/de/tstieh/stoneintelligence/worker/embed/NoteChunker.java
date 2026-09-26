package de.tstieh.stoneintelligence.worker.embed;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import de.tstieh.stoneintelligence.stoneai.vault.Frontmatter;

/**
 * Teilt eine Notiz fuer die Embeddings in Abschnitte (ADR 0012): je Ueberschrift einer, lange
 * Abschnitte an Absaetzen weiter geteilt. Jeder Abschnitt traegt den Titel der Notiz vorn, damit
 * "Einleitung" nicht ohne Zusammenhang dasteht. Das Frontmatter zaehlt nicht mit.
 */
public final class NoteChunker {

    /** Etwa das, was in das Modellfenster (512 Token) passt. */
    public static final int MAX_CHARS = 1_500;
    /** Mehr Abschnitte bringen fuer die Aehnlichkeit nichts, kosten aber Rechenzeit. */
    public static final int MAX_CHUNKS = 50;
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}[ \\t]+(.+?)[ \\t#]*$");

    public record Section(String heading, String text) {
    }

    private NoteChunker() {
    }

    public static List<Section> chunks(String title, String text) {
        var body = Frontmatter.of(text == null ? "" : text).body();
        var sections = new ArrayList<Section>();
        var heading = title;
        var start = 0;
        var matcher = HEADING.matcher(body);
        while (matcher.find()) {
            addSection(sections, title, heading, body.substring(start, matcher.start()));
            heading = matcher.group(1).strip();
            start = matcher.end();
        }
        addSection(sections, title, heading, body.substring(start));
        if (sections.isEmpty()) {
            sections.add(new Section(title, title));
        }
        return sections.size() > MAX_CHUNKS ? List.copyOf(sections.subList(0, MAX_CHUNKS)) : List.copyOf(sections);
    }

    private static void addSection(List<Section> sections, String title, String heading, String content) {
        var trimmed = content.strip();
        if (trimmed.isEmpty() || sections.size() >= MAX_CHUNKS) {
            return;
        }
        var prefix = heading.equals(title) ? title : title + " – " + heading;
        var piece = new StringBuilder();
        for (var paragraph : trimmed.split("\\n\\s*\\n")) {
            if (piece.length() > 0 && piece.length() + paragraph.length() > MAX_CHARS) {
                sections.add(new Section(heading, prefix + "\n\n" + piece.toString().strip()));
                piece.setLength(0);
            }
            piece.append(paragraph.length() > MAX_CHARS ? paragraph.substring(0, MAX_CHARS) : paragraph).append("\n\n");
        }
        if (piece.length() > 0) {
            sections.add(new Section(heading, prefix + "\n\n" + piece.toString().strip()));
        }
    }
}
