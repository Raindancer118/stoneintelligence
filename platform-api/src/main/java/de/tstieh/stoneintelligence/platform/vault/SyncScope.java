package de.tstieh.stoneintelligence.platform.vault;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import de.tstieh.stoneintelligence.platform.identity.AccessResolver;

/**
 * Arbeitsbereiche eines Geraets (ADR 0013): Ordner und einzelne angeheftete Eintraege, die es
 * synchron haelt. Leer heisst: der ganze Vault. Nur die aeussersten Bereiche bleiben uebrig, damit
 * sich Abfragen nicht ueberschneiden.
 */
public record SyncScope(List<String> areas) {

    public static final int MAX_AREAS = 200;

    public static SyncScope wholeVault() {
        return new SyncScope(List.of());
    }

    public static SyncScope of(List<String> requested) {
        if (requested.size() > MAX_AREAS) {
            throw new IllegalArgumentException("at most " + MAX_AREAS + " areas");
        }
        var sorted = new TreeSet<String>();
        for (var area : requested) {
            var normalized = AccessResolver.normalize(area);
            if (normalized.isEmpty()) {
                return wholeVault();
            }
            sorted.add(normalized);
        }
        var outermost = new ArrayList<String>();
        for (var area : sorted) {
            if (outermost.stream().noneMatch(outer -> isAtOrBelow(area, outer))) {
                outermost.add(area);
            }
        }
        return new SyncScope(List.copyOf(outermost));
    }

    public boolean isWholeVault() {
        return areas.isEmpty();
    }

    public boolean covers(String path) {
        return isWholeVault() || areas.stream().anyMatch(area -> isAtOrBelow(path, area));
    }

    /** Ordner in einem Bereich oder darueber (sonst fehlte lokal der Weg dorthin). */
    public boolean touchesFolder(String folder) {
        return isWholeVault() || areas.stream().anyMatch(area -> isAtOrBelow(folder, area) || area.startsWith(folder + "/"));
    }

    private static boolean isAtOrBelow(String path, String area) {
        return path.equals(area) || path.startsWith(area + "/");
    }
}
