package de.tstieh.stoneintelligence.platform.vault;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import de.tstieh.stoneintelligence.platform.sync.relay.UpdateInfo;

/** Fasst das Update-Log einer Notiz zu Versionen zusammen (Tippen schreibt ein Update je Tastendruck). */
public final class NoteVersions {

    /** Laenger nichts geschrieben: die naechste Aenderung beginnt eine neue Version. */
    static final Duration PAUSE = Duration.ofMinutes(10);
    /** Auch ohne Pause hoechstens eine Stunde je Version, damit lange Sitzungen Zwischenstaende behalten. */
    static final Duration LONGEST = Duration.ofHours(1);

    private NoteVersions() {
    }

    /** Neueste Version zuerst. */
    public static List<NoteVersion> group(List<UpdateInfo> log) {
        var versions = new ArrayList<NoteVersion>();
        UpdateInfo first = null;
        UpdateInfo last = null;
        var count = 0;
        for (var update : log) {
            if (first != null && startsNewVersion(first, last, update)) {
                versions.add(version(first, last, count));
                first = null;
            }
            if (first == null) {
                first = update;
                count = 0;
            }
            last = update;
            count++;
        }
        if (first != null) {
            versions.add(version(first, last, count));
        }
        return versions.reversed();
    }

    private static boolean startsNewVersion(UpdateInfo first, UpdateInfo last, UpdateInfo next) {
        return !Objects.equals(first.actor(), next.actor())
            || Duration.between(last.createdAt(), next.createdAt()).compareTo(PAUSE) > 0
            || Duration.between(first.createdAt(), next.createdAt()).compareTo(LONGEST) >= 0;
    }

    private static NoteVersion version(UpdateInfo first, UpdateInfo last, int count) {
        return new NoteVersion(last.serverSequence(), first.serverSequence(), first.actor(), first.createdAt(), last.createdAt(), count);
    }
}
