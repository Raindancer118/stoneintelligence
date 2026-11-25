package de.tstieh.stoneintelligence.platform.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Nutzereingabe → Postgres-tsquery: jedes Wort als Praefix, alle muessen vorkommen. Nur Buchstaben
 * und Ziffern bleiben uebrig, damit keine tsquery-Syntax aus der Eingabe stammt.
 */
public final class SearchQuery {

    static final int MAX_WORDS = 8;
    static final int MAX_QUESTION_WORDS = 12;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int MIN_QUESTION_WORD = 3;
    /** Fragewoerter und Fuellwoerter, die in fast jeder Notiz vorkommen und die Suche nur verwaessern. */
    private static final Set<String> STOP_WORDS = Set.of(
        "wer", "wie", "was", "wann", "wo", "wohin", "woher", "warum", "wieso", "weshalb", "welche", "welcher", "welches", "welchen",
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "und", "oder", "aber", "nicht", "ist",
        "sind", "war", "waren", "wird", "werden", "hat", "haben", "hatte", "kann", "gibt", "mit", "von", "vom", "zum", "zur", "für",
        "auf", "aus", "bei", "nach", "über", "uns", "mir", "mich", "dir", "ich", "sich", "wir", "sie", "ihr", "man", "auch", "noch",
        "the", "and", "are", "were", "what", "when", "where", "which", "who", "why", "how", "does", "did", "for", "with",
        "from", "this", "that", "these", "those", "has", "have", "can", "about", "into", "not");

    private SearchQuery() {
    }

    public static Optional<String> tsquery(String input) {
        var words = new ArrayList<String>();
        var matcher = WORD.matcher(input.toLowerCase(Locale.ROOT));
        while (matcher.find() && words.size() < MAX_WORDS) {
            words.add(matcher.group() + ":*");
        }
        return words.isEmpty() ? Optional.empty() : Optional.of(String.join(" & ", words));
    }

    /**
     * Eine Frage → tsquery, bei der <em>ein</em> inhaltstragendes Wort genuegt (Treffer mit mehr Woertern ranken
     * hoeher): UND ueber alle Woerter eines Satzes fand nie etwas.
     */
    public static Optional<String> anyOf(String question) {
        var words = contentWords(question);
        return words.isEmpty() ? Optional.empty() : Optional.of(words.stream().map(word -> word + ":*").collect(Collectors.joining(" | ")));
    }

    /** Die inhaltstragenden Woerter einer Frage, kleingeschrieben und ohne Wiederholung. */
    public static List<String> contentWords(String question) {
        var words = new LinkedHashSet<String>();
        var matcher = WORD.matcher(question.toLowerCase(Locale.ROOT));
        while (matcher.find() && words.size() < MAX_QUESTION_WORDS) {
            var word = matcher.group();
            if (word.length() >= MIN_QUESTION_WORD && !STOP_WORDS.contains(word)) {
                words.add(word);
            }
        }
        return List.copyOf(words);
    }
}
