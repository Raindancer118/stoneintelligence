package de.raindancer118.stoneai.vault;

import java.util.Locale;

/**
 * Turns a note title into a file name Obsidian and every common filesystem accept. The title
 * itself is never changed — it lives on in the frontmatter, so a note can be called
 * {@code Menge A/B: [Teil] #1} while its file is not.
 */
public final class NoteFileName {

    /** Obsidian refuses these outright, or they break links and paths. */
    private static final String ILLEGAL = "\\/:*?\"<>|#^[]";
    private static final int MAX_LENGTH = 120;

    private NoteFileName() {
    }

    public static String forTitle(String title) {
        StringBuilder name = new StringBuilder();
        for (char character : title.strip().toCharArray()) {
            if (ILLEGAL.indexOf(character) >= 0 || Character.isISOControl(character)) {
                name.append('-');
            } else if (Character.isWhitespace(character)) {
                name.append('-');
            } else {
                name.append(character);
            }
        }
        String cleaned = name.toString()
                .replaceAll("-{2,}", "-")
                .replaceAll("^[-.]+", "")
                .replaceAll("[-.]+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "Notiz";
        }
        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return cleaned + ".md";
    }

    /** Whether two file names would collide on a case-insensitive filesystem. */
    public static boolean sameFile(String left, String right) {
        return left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
    }
}
