package de.tstieh.stoneintelligence.stoneai.note;

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
     * The title as a person would type it: typographic hyphens and spaces (which models like to
     * emit - {@code HWS\u2011Distorsion}) become plain ones, soft hyphens vanish, runs of
     * whitespace collapse. Without this two spellings that look identical end up as two notes
     * and a link that looks right leads nowhere.
     */
    public static String plain(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\u2010\u2011\u2012\u2043\u2212]", "-")
                .replace("\u00ad", "")
                .replaceAll("[\u00a0\u2007\u202f\\s]+", " ")
                .strip();
    }

    /**
     * Whether two titles name the same note. Close in Jaro-Winkler terms is necessary but not
     * enough: German compounds share long prefixes ({@code Schadenmeldung}/{@code Schadennummer})
     * and titles often differ only in a date - so the numbers must agree and the spelling may
     * differ by no more than a typo or two.
     */
    public static boolean sameConcept(String left, String right, double threshold) {
        String a = normalise(left);
        String b = normalise(right);
        if (a.equals(b)) {
            return true;
        }
        if (a.isEmpty() || b.isEmpty() || !digits(a).equals(digits(b))) {
            return false;
        }
        int allowed = Math.max(2, Math.max(a.length(), b.length()) / 6);
        return jaroWinkler(a, b) >= threshold && editDistance(a, b) <= allowed;
    }

    private static String digits(String text) {
        return text.replaceAll("[^0-9]+", " ").strip();
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
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
