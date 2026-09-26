package de.tstieh.stoneintelligence.domain.link;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links in Markdown-Text setzen und wieder herausnehmen (ADR 0012), ohne jemals Text zu aendern:
 * ein Link ist immer nur eingefuegtes Markup um ein Wort, das schon dasteht ({@code [[Ziel|Wort]]}),
 * oder eine neue Zeile unter {@code ## Verwandt}. Rueckgaengig nimmt genau dieses Markup wieder
 * heraus - auch wenn inzwischen weitergeschrieben wurde.
 *
 * <p>Nie verlinkt wird in Frontmatter, Code, Ueberschriften, vorhandenen Links, Formeln,
 * Kommentaren, URLs und Tags. Framework-frei, damit Server (setzt die Links) und Worker (sucht sie)
 * dieselben Regeln benutzen.
 */
public final class LinkText {

    public static final String RELATED_HEADING = "## Verwandt";

    public enum Placement { INLINE, RELATED }

    /**
     * Ergebnis einer Verlinkung. {@code markup}: bei {@code INLINE} der eingefuegte Link samt Wort,
     * bei {@code RELATED} die Listenzeile. {@code anchor}: das verlinkte Wort, wie es im Text steht.
     * {@code sectionPrefix}: was fuer einen neuen Abschnitt "Verwandt" angehaengt wurde (sonst leer).
     */
    public record Insertion(String text, Placement placement, String markup, String anchor, boolean createdSection,
                            String sectionPrefix) {
    }

    private static final Pattern MASKED = Pattern.compile(String.join("|",
        "!?\\[\\[[^\\]\\n]*\\]\\]",                // Wikilinks und Einbettungen
        "\\[[^\\]\\n]*\\]\\([^)\\n]*\\)",          // Markdown-Links
        "`[^`\\n]*`",                              // Inline-Code
        "\\$\\$[\\s\\S]*?\\$\\$",                  // Formelblock
        "\\$[^$\\n]+\\$",                          // Formel
        "%%[\\s\\S]*?%%",                          // Obsidian-Kommentar
        "<!--[\\s\\S]*?-->",                       // HTML-Kommentar
        "<https?://[^>\\s]*>",                     // Autolink
        "https?://\\S+",                           // URL
        "(?<![\\p{L}\\p{N}/#])#[\\p{L}\\p{N}_/-]+" // Tag
    ));
    private static final Pattern HEADING_LINE = Pattern.compile("(?m)^[ ]{0,3}#{1,6}[ \\t].*$");
    private static final Pattern RELATED = Pattern.compile("(?m)^##[ \\t]+Verwandt[ \\t]*$");
    private static final Pattern ANY_HEADING = Pattern.compile("(?m)^[ ]{0,3}#{1,6}[ \\t]");

    private LinkText() {
    }

    /** Prueft viele Stellen desselben Texts, ohne die gesperrten Bereiche jedes Mal neu zu bestimmen. */
    public static final class Scanner {
        private final String text;
        private final boolean[] masked;

        private Scanner(String text) {
            this.text = text;
            this.masked = masked(text);
        }

        /** Ob an {@code [start, end)} ein Link stehen darf: innerhalb des Texts, ganze Woerter, nichts Gesperrtes. */
        public boolean linkable(int start, int end) {
            return start >= 0 && end <= text.length() && start < end
                && !isWordChar(text, start - 1) && !isWordChar(text, end) && !anyMasked(masked, start, end);
        }
    }

    public static Scanner scanner(String text) {
        return new Scanner(text);
    }

    /** Erste Stelle, an der {@code phrase} als ganzes Wort steht und verlinkt werden darf (Gross/klein egal). */
    public static OptionalInt findMention(String text, String phrase) {
        var wanted = phrase == null ? "" : phrase.strip();
        if (wanted.isEmpty()) {
            return OptionalInt.empty();
        }
        var masked = masked(text);
        for (var i = 0; i + wanted.length() <= text.length(); i++) {
            if (!text.regionMatches(true, i, wanted, 0, wanted.length())) {
                continue;
            }
            var end = i + wanted.length();
            if (isWordChar(text, i - 1) || isWordChar(text, end) || anyMasked(masked, i, end)) {
                continue;
            }
            return OptionalInt.of(i);
        }
        return OptionalInt.empty();
    }

    /** Ob der Text schon auf {@code target} verlinkt (auch mit Anzeigetext oder Sprungmarke). */
    public static boolean linksTo(String text, String target) {
        return Pattern.compile("\\[\\[\\s*" + Pattern.quote(target.strip()) + "\\s*(\\||#|\\]\\])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }

    /**
     * Verlinkt {@code anchor} mit {@code target} (Obsidian-Linkziel, z. B. {@code Licht} oder
     * {@code Physik/Licht}). Steht das Wort nirgends passend, landet der Link - wenn erlaubt - unter
     * "Verwandt". Leer, wenn der Text schon auf das Ziel verlinkt oder es keinen Platz gibt.
     */
    public static Optional<Insertion> insert(String text, String target, String anchor, boolean allowRelated) {
        if (linksTo(text, target)) {
            return Optional.empty();
        }
        var mention = findMention(text, anchor);
        if (mention.isPresent()) {
            var start = mention.getAsInt();
            var end = start + anchor.strip().length();
            var shown = text.substring(start, end);
            var prefix = shown.equals(target) ? "[[" : "[[" + target + "|";
            var markup = prefix + shown + "]]";
            return Optional.of(new Insertion(text.substring(0, start) + markup + text.substring(end),
                Placement.INLINE, markup, shown, false, ""));
        }
        if (!allowRelated) {
            return Optional.empty();
        }
        var line = "- [[" + target + "]]";
        var heading = relatedHeading(text);
        if (heading.isPresent()) {
            var at = endOfSectionContent(text, heading.get());
            var insertion = (at > 0 && text.charAt(at - 1) != '\n' ? "\n" : "") + line + "\n";
            return Optional.of(new Insertion(text.substring(0, at) + insertion + text.substring(at),
                Placement.RELATED, line, null, false, ""));
        }
        var sectionPrefix = (text.isEmpty() || text.endsWith("\n") ? "" : "\n") + "\n" + RELATED_HEADING + "\n\n";
        return Optional.of(new Insertion(text + sectionPrefix + line + "\n", Placement.RELATED, line, null, true, sectionPrefix));
    }

    /** Nimmt genau das eingefuegte Markup wieder heraus; was jemand seither geaendert hat, bleibt. */
    public static String remove(String text, List<Insertion> insertions) {
        var result = text;
        for (var insertion : insertions.reversed()) {
            if (insertion.placement() == Placement.INLINE) {
                var at = result.indexOf(insertion.markup());
                if (at >= 0) {
                    result = result.substring(0, at) + insertion.anchor() + result.substring(at + insertion.markup().length());
                }
                continue;
            }
            var lineMatcher = Pattern.compile("(?m)^" + Pattern.quote(insertion.markup()) + "[ \\t]*(\\r?\\n|$)").matcher(result);
            if (!lineMatcher.find()) {
                continue;
            }
            result = result.substring(0, lineMatcher.start()) + result.substring(lineMatcher.end());
            if (insertion.createdSection()) {
                result = dropEmptySection(result, insertion.sectionPrefix());
            }
        }
        return result;
    }

    /** Einen selbst angelegten, inzwischen leeren Abschnitt "Verwandt" samt eingefuegter Leerzeilen entfernen. */
    private static String dropEmptySection(String text, String sectionPrefix) {
        var at = text.lastIndexOf(sectionPrefix);
        if (at < 0) {
            return text;
        }
        var contentStart = at + sectionPrefix.length();
        var next = ANY_HEADING.matcher(text);
        var sectionEnd = next.find(contentStart) ? next.start() : text.length();
        if (!text.substring(contentStart, sectionEnd).isBlank()) {
            return text;
        }
        return text.substring(0, at) + text.substring(sectionEnd);
    }

    private static Optional<Matcher> relatedHeading(String text) {
        var masked = maskedWithoutHeadings(text);
        var matcher = RELATED.matcher(text);
        while (matcher.find()) {
            if (!anyMasked(masked, matcher.start(), matcher.end())) {
                return Optional.of(matcher);
            }
        }
        return Optional.empty();
    }

    /** Ende der letzten nicht leeren Zeile des Abschnitts - dort kommt der neue Eintrag hin. */
    private static int endOfSectionContent(String text, Matcher heading) {
        var next = ANY_HEADING.matcher(text);
        var sectionEnd = next.find(heading.end()) ? next.start() : text.length();
        var lastContent = heading.end();
        var lineStart = text.indexOf('\n', heading.end());
        while (lineStart >= 0 && lineStart < sectionEnd) {
            var lineEnd = text.indexOf('\n', lineStart + 1);
            var end = lineEnd < 0 || lineEnd > sectionEnd ? sectionEnd : lineEnd;
            if (!text.substring(lineStart + 1, end).isBlank()) {
                lastContent = end;
            }
            lineStart = lineEnd;
        }
        if (lastContent == heading.end()) {
            // Nur die Ueberschrift: der Eintrag kommt nach einer Leerzeile darunter.
            return Math.min(text.length(), heading.end() + (text.startsWith("\n", heading.end()) ? 1 : 0));
        }
        return Math.min(text.length(), lastContent + (text.startsWith("\n", lastContent) ? 1 : 0));
    }

    private static boolean[] masked(String text) {
        var masked = maskedWithoutHeadings(text);
        var headings = HEADING_LINE.matcher(text);
        while (headings.find()) {
            mark(masked, headings.start(), headings.end());
        }
        return masked;
    }

    /** Alles Gesperrte ausser Ueberschriften - die Ueberschrift "Verwandt" selbst muss auffindbar bleiben. */
    private static boolean[] maskedWithoutHeadings(String text) {
        var masked = new boolean[text.length()];
        maskFrontmatter(text, masked);
        maskFences(text, masked);
        var matcher = MASKED.matcher(text);
        while (matcher.find()) {
            mark(masked, matcher.start(), matcher.end());
        }
        return masked;
    }

    private static void maskFrontmatter(String text, boolean[] masked) {
        if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) {
            return;
        }
        var close = Pattern.compile("(?m)^(---|\\.\\.\\.)[ \\t]*$").matcher(text);
        if (close.find(3)) {
            mark(masked, 0, close.end());
        }
    }

    /** Codebloecke zeilenweise: von ``` bzw. ~~~ bis zur gleichen Zaunmarke (oder zum Ende). */
    private static void maskFences(String text, boolean[] masked) {
        String open = null;
        var blockStart = 0;
        var lineStart = 0;
        while (lineStart <= text.length()) {
            var lineEnd = text.indexOf('\n', lineStart);
            var end = lineEnd < 0 ? text.length() : lineEnd;
            var line = text.substring(lineStart, end).stripLeading();
            if (open == null && (line.startsWith("```") || line.startsWith("~~~"))) {
                open = line.substring(0, 3);
                blockStart = lineStart;
            } else if (open != null && line.startsWith(open)) {
                mark(masked, blockStart, end);
                open = null;
            }
            if (lineEnd < 0) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        if (open != null) {
            mark(masked, blockStart, text.length());
        }
    }

    private static void mark(boolean[] masked, int start, int end) {
        for (var i = Math.max(0, start); i < Math.min(masked.length, end); i++) {
            masked[i] = true;
        }
    }

    private static boolean anyMasked(boolean[] masked, int start, int end) {
        for (var i = start; i < end; i++) {
            if (masked[i]) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWordChar(String text, int index) {
        return index >= 0 && index < text.length() && Character.isLetterOrDigit(text.charAt(index));
    }
}
