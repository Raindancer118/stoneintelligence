package de.tstieh.stoneintelligence.platform.files;

import java.util.Locale;
import java.util.Set;

/** Der beim Hochladen angegebene Typ ist Nutzereingabe: ausgeliefert wird nur, was keine Skripte ausfuehren kann. */
final class DownloadContentTypes {

    private static final Set<String> SAFE = Set.of("application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp",
        "image/avif", "image/bmp", "text/plain", "text/markdown", "text/csv", "application/json", "application/zip",
        "audio/mpeg", "audio/ogg", "audio/wav", "audio/mp4", "video/mp4", "video/webm");

    private DownloadContentTypes() {
    }

    static String safe(String stored) {
        var type = stored == null ? "" : stored.strip().toLowerCase(Locale.ROOT);
        return SAFE.contains(type) ? type : "application/octet-stream";
    }
}
