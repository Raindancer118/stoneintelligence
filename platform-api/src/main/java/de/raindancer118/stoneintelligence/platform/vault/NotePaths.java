package de.raindancer118.stoneintelligence.platform.vault;

import java.util.Arrays;
import java.util.Locale;

/** Welche Pfade eine Notiz haben darf - fuer Menschen (NoteController) und KI gleich. */
public final class NotePaths {

    private NotePaths() {
    }

    public static boolean isValid(String path) {
        return path != null && !path.isBlank() && path.length() <= 1024 && path.equals(path.strip())
            && path.toLowerCase(Locale.ROOT).endsWith(".md")
            && path.chars().noneMatch(c -> Character.isISOControl(c) || "\\:*?\"<>|".indexOf(c) >= 0)
            && Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isBlank() || part.startsWith("."));
    }
}
