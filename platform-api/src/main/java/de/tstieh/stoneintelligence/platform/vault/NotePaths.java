package de.tstieh.stoneintelligence.platform.vault;

import java.util.Arrays;
import java.util.Locale;

/** Welche Pfade eine Notiz haben darf - fuer Menschen (NoteController) und KI gleich. */
public final class NotePaths {

    private NotePaths() {
    }

    public static boolean isValid(String path) {
        return hasValidShape(path, ".md");
    }

    /** Pfad eines Canvas (ADR 0018): gleiche Regeln, Endung {@code .canvas}. */
    public static boolean isValidCanvas(String path) {
        return hasValidShape(path, NoteKind.CANVAS_EXTENSION);
    }

    private static boolean hasValidShape(String path, String extension) {
        return path != null && !path.isBlank() && path.length() <= 1024 && path.equals(path.strip())
            && path.length() > extension.length() && path.toLowerCase(Locale.ROOT).endsWith(extension)
            && path.chars().noneMatch(c -> Character.isISOControl(c) || "\\:*?\"<>|".indexOf(c) >= 0)
            && Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isBlank() || part.startsWith("."));
    }
}
