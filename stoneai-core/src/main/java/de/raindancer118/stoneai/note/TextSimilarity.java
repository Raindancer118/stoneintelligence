package de.raindancer118.stoneai.note;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Title comparison for deduplication. Deliberately deterministic rather than model-based: whether
 * two notes are "the same" must not change between runs, and a threshold a user can tune beats a
 * model's opinion they cannot inspect.
 */
public final class TextSimilarity {

    private TextSimilarity() {
    }

    /**
     * A comparison key: lower-cased, accent-folded, punctuation-free, and with the German plural
     * and inflection endings that make {@code Äquivalenzrelation} and {@code Äquivalenzrelationen}
     * the same concept trimmed off.
     */
    public static String normalise(String text) {
        String folded = Normalizer.normalize(text.toLowerCase(Locale.GERMAN), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace("ß", "ss")
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
        return stem(folded);
    }

    private static String stem(String text) {
        StringBuilder stemmed = new StringBuilder();
        for (String word : text.split(" ")) {
            stemmed.append(stemWord(word)).append(' ');
        }
        return stemmed.toString().strip();
    }

    private static String stemWord(String word) {
        if (word.length() <= 4) {
            return word;
        }
        for (String ending : new String[]{"en", "er", "es", "e", "n", "s"}) {
            if (word.endsWith(ending) && word.length() - ending.length() >= 4) {
                return word.substring(0, word.length() - ending.length());
            }
        }
        return word;
    }

    /** Jaro-Winkler similarity of two normalised strings, in {@code [0, 1]}. */
    public static double similarity(String left, String right) {
        String a = normalise(left);
        String b = normalise(right);
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        return jaroWinkler(a, b);
    }

    private static double jaroWinkler(String a, String b) {
        double jaro = jaro(a, b);
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(a.length(), b.length())) && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    private static double jaro(String a, String b) {
        int window = Math.max(a.length(), b.length()) / 2 - 1;
        boolean[] matchedA = new boolean[a.length()];
        boolean[] matchedB = new boolean[b.length()];
        int matches = 0;

        for (int i = 0; i < a.length(); i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(i + window + 1, b.length());
            for (int j = from; j < to; j++) {
                if (matchedB[j] || a.charAt(i) != b.charAt(j)) {
                    continue;
                }
                matchedA[i] = true;
                matchedB[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0;
        }

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!matchedA[i]) {
                continue;
            }
            while (!matchedB[k]) {
                k++;
            }
            if (a.charAt(i) != b.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        double m = matches;
        return (m / a.length() + m / b.length() + (m - transpositions / 2.0) / m) / 3.0;
    }
}
