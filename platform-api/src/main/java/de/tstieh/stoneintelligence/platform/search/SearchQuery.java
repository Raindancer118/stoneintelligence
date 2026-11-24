package de.tstieh.stoneintelligence.platform.search;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Nutzereingabe → Postgres-tsquery: jedes Wort als Praefix, alle muessen vorkommen. Nur Buchstaben
 * und Ziffern bleiben uebrig, damit keine tsquery-Syntax aus der Eingabe stammt.
 */
public final class SearchQuery {

    static final int MAX_WORDS = 8;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

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
}
