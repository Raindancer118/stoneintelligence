package de.tstieh.stoneintelligence.platform.files;

import java.util.Locale;
import de.tstieh.stoneintelligence.platform.vault.FolderPaths;

/** Welche Pfade eine Datei haben darf: wie Ordner (keine versteckten Teile, keine Sonderzeichen), aber nie Markdown. */
public final class FilePaths {

    private FilePaths() {
    }

    public static boolean isValid(String path) {
        return FolderPaths.isValid(path) && !path.toLowerCase(Locale.ROOT).endsWith(".md") && !path.endsWith("/");
    }
}
