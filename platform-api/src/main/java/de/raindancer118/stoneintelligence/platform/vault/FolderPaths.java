package de.raindancer118.stoneintelligence.platform.vault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Welche Ordnerpfade synchronisiert werden - dieselben Regeln wie {@link NotePaths}, ohne Endung. */
public final class FolderPaths {

    private FolderPaths() {
    }

    public static boolean isValid(String path) {
        return path != null && !path.isBlank() && path.length() <= 1024 && path.equals(path.strip())
            && path.chars().noneMatch(c -> Character.isISOControl(c) || "\\:*?\"<>|".indexOf(c) >= 0)
            && Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isBlank() || part.startsWith("."));
    }

    /** Alle Ordner oberhalb einer Notiz, vom obersten abwaerts: {@code A/B/C.md -> [A, A/B]}. */
    public static List<String> parentsOf(String notePath) {
        var parents = new ArrayList<String>();
        for (var slash = notePath.indexOf('/'); slash > 0; slash = notePath.indexOf('/', slash + 1)) {
            parents.add(notePath.substring(0, slash));
        }
        return parents;
    }

    /** Der Ordner selbst und alle Ordner darueber: {@code A/B -> [A, A/B]}. */
    public static List<String> withParents(String folderPath) {
        var all = new ArrayList<>(parentsOf(folderPath));
        all.add(folderPath);
        return all;
    }

    public static boolean isInside(String path, String folder) {
        return path.equals(folder) || path.startsWith(folder + "/");
    }
}
