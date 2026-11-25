package de.tstieh.stoneintelligence.platform.comment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Erwähnungen stehen im Kommentartext als {@code @[Name](subject)}. Der Server leitet sie aus dem Text ab,
 * statt eine zweite Liste zu glauben: Text und Benachrichtigte können so nicht auseinanderlaufen.
 */
final class Mentions {

    private static final Pattern TOKEN = Pattern.compile("@\\[([^\\]\\n]{1,100})]\\(([^)\\s]{1,200})\\)");

    private Mentions() {
    }

    /** Subject → Name, wie ihn die schreibende Person sah; jede Person einmal, in Textreihenfolge. */
    static Map<String, String> of(String body) {
        var found = new LinkedHashMap<String, String>();
        var matcher = TOKEN.matcher(body);
        while (matcher.find()) {
            found.putIfAbsent(matcher.group(2), matcher.group(1));
        }
        return found;
    }

    /** Für Vorschau und Mail: {@code @[Ben](ben)} wird zu {@code @Ben}. */
    static String plain(String body) {
        return TOKEN.matcher(body).replaceAll(match -> java.util.regex.Matcher.quoteReplacement("@" + match.group(1)));
    }
}
