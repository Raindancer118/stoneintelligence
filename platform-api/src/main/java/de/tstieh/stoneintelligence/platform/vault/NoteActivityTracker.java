package de.tstieh.stoneintelligence.platform.vault;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.stereotype.Component;

/**
 * Haelt "zuletzt geoeffnet" und "zuletzt bearbeitet" fuer den Sync-Kanal fest. Dort ist jeder
 * Tastendruck ein Update - deshalb hoechstens einmal je {@link #INTERVAL} und Notiz/Person/Art,
 * statt die Datenbank im Takt der Tastatur zu beschreiben.
 */
@Component
public class NoteActivityTracker {

    static final Duration INTERVAL = Duration.ofMinutes(1);

    private final NoteRepository notes;
    private final Clock clock;
    private final Map<String, Instant> lastWritten = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public NoteActivityTracker(NoteRepository notes) {
        this(notes, Clock.systemUTC());
    }

    NoteActivityTracker(NoteRepository notes, Clock clock) {
        this.notes = notes;
        this.clock = clock;
    }

    public void opened(VaultId vaultId, NoteId noteId, String actor) {
        if (due("o", noteId, actor)) {
            notes.markOpened(vaultId, noteId, actor, clock.instant());
        }
    }

    public void edited(VaultId vaultId, NoteId noteId, String actor) {
        if (due("e", noteId, actor)) {
            notes.markEdited(vaultId, noteId, actor, clock.instant());
        }
    }

    private boolean due(String kind, NoteId noteId, String actor) {
        var now = clock.instant();
        var key = kind + ":" + noteId.value() + ":" + actor;
        var previous = lastWritten.get(key);
        if (previous != null && now.isBefore(previous.plus(INTERVAL))) {
            return false;
        }
        lastWritten.put(key, now);
        // Speicher-Hygiene: alte Eintraege sind fuer die Drossel bedeutungslos.
        if (lastWritten.size() > 10_000) {
            lastWritten.values().removeIf(at -> at.isBefore(now.minus(INTERVAL)));
        }
        return true;
    }
}
