package de.tstieh.stoneintelligence.worker.link;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import de.tstieh.stoneintelligence.domain.link.LinkText;

/**
 * Stufe 1 der Verlinkung (ADR 0012): wo nennt eine Notiz Titel oder Alias einer anderen woertlich?
 * Geht jeden Text einmal Wort fuer Wort durch und schlaegt Wortfolgen in einer Tabelle aller Namen
 * nach - statt jeden Namen in jedem Text zu suchen, was bei grossen Vaults quadratisch teuer waere.
 * Ob an der Stelle ein Link stehen darf (kein Code, kein Link, keine Ueberschrift ...), entscheidet
 * {@link LinkText} - dieselbe Regel, nach der der Server den Link setzt.
 */
public final class MentionFinder {

    /** Kuerzere Namen ("KI", "IT") treffen zu viel Zufaelliges. */
    static final int MIN_NAME_LENGTH = 3;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    public record Mention(String targetNoteId, String anchor, int start) {
    }

    /** Name (klein, Woerter mit einem Leerzeichen) -> Notizen, die so heissen. */
    private final Map<String, List<String>> byName = new HashMap<>();
    private final int maxWords;

    /** @param namesByNote je Notiz-Id ihr Titel und ihre Aliase */
    public MentionFinder(Map<String, List<String>> namesByNote) {
        var longest = 1;
        for (var entry : namesByNote.entrySet()) {
            for (var name : entry.getValue()) {
                var key = String.join(" ", words(name)).toLowerCase(Locale.ROOT);
                if (key.length() < MIN_NAME_LENGTH || key.chars().noneMatch(Character::isLetter)) {
                    continue;
                }
                var words = words(name);
                var targets = byName.computeIfAbsent(key, k -> new ArrayList<>());
                if (!targets.contains(entry.getKey())) {
                    targets.add(entry.getKey());
                }
                longest = Math.max(longest, words.size());
            }
        }
        this.maxWords = longest;
    }

    /** Je anderer Notiz die erste verlinkbare Nennung, in der Reihenfolge des Texts; laengere Namen zuerst. */
    public List<Mention> find(String sourceNoteId, String text) {
        var scanner = LinkText.scanner(text);
        var tokens = new ArrayList<int[]>();
        var matcher = WORD.matcher(text);
        while (matcher.find()) {
            tokens.add(new int[] {matcher.start(), matcher.end()});
        }
        var found = new LinkedHashMap<String, Mention>();
        var coveredUntil = -1;
        for (var i = 0; i < tokens.size(); i++) {
            if (tokens.get(i)[0] < coveredUntil) {
                continue;
            }
            // Laengste passende Wortfolge ab hier - "Grüne Pflanze" vor "Pflanze".
            for (var n = Math.min(maxWords, tokens.size() - i); n >= 1; n--) {
                var start = tokens.get(i)[0];
                var end = tokens.get(i + n - 1)[1];
                if (!onlySpacesBetween(text, tokens, i, n)) {
                    continue;
                }
                var key = normalised(text, tokens, i, n);
                var targets = byName.get(key);
                if (targets == null || !scanner.linkable(start, end)) {
                    continue;
                }
                var anchor = text.substring(start, end);
                var matched = false;
                for (var target : targets) {
                    if (!target.equals(sourceNoteId) && !found.containsKey(target)) {
                        found.put(target, new Mention(target, anchor, start));
                        matched = true;
                    }
                }
                if (matched) {
                    coveredUntil = end;
                    break;
                }
            }
        }
        return List.copyOf(found.values());
    }

    private static boolean onlySpacesBetween(String text, List<int[]> tokens, int from, int count) {
        for (var k = from; k < from + count - 1; k++) {
            var gap = text.substring(tokens.get(k)[1], tokens.get(k + 1)[0]);
            if (gap.isEmpty() || !gap.chars().allMatch(c -> c == ' ' || c == '\t')) {
                return false;
            }
        }
        return true;
    }

    private static String normalised(String text, List<int[]> tokens, int from, int count) {
        var key = new StringBuilder();
        for (var k = from; k < from + count; k++) {
            if (k > from) {
                key.append(' ');
            }
            key.append(text, tokens.get(k)[0], tokens.get(k)[1]);
        }
        return key.toString().toLowerCase(Locale.ROOT);
    }

    private static List<String> words(String name) {
        var words = new ArrayList<String>();
        var matcher = WORD.matcher(name == null ? "" : name);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }
}
