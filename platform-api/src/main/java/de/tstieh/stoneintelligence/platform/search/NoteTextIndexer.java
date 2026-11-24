package de.tstieh.stoneintelligence.platform.search;

import java.util.function.Supplier;
import de.tstieh.stoneintelligence.platform.sync.relay.SnapshotStore;
import de.tstieh.stoneintelligence.platform.sync.relay.UpdateRecord;
import de.tstieh.stoneintelligence.platform.sync.yjs.YjsBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Haelt {@code note_texts} aktuell: holt geaenderte Notizen aus der Warteschlange (Datenbank-Trigger)
 * und schreibt ihren Text. Verschluesselte Notizen liest der Server nicht - ihr Text faellt heraus.
 */
@Component
public class NoteTextIndexer {

    static final int BATCH = 50;
    private static final Logger log = LoggerFactory.getLogger(NoteTextIndexer.class);

    private final NoteTextIndex index;
    private final SnapshotStore snapshots;
    private final Supplier<YjsBridge> yjs;

    @Autowired
    public NoteTextIndexer(NoteTextIndex index, SnapshotStore snapshots, ObjectProvider<YjsBridge> yjs) {
        this(index, snapshots, (Supplier<YjsBridge>) yjs::getObject);
    }

    NoteTextIndexer(NoteTextIndex index, SnapshotStore snapshots, Supplier<YjsBridge> yjs) {
        this.index = index;
        this.snapshots = snapshots;
        this.yjs = yjs;
    }

    @Scheduled(fixedDelayString = "${stoneintelligence.search.index-delay-ms:3000}", initialDelay = 10_000)
    public void scheduled() {
        try {
            // Bestand nach dem Einspielen: mehrere Stapel je Lauf, aber nie endlos (scheiternde Notizen kommen zurueck).
            for (int round = 0; round < 20 && runOnce() == BATCH; round++) {
            }
        } catch (RuntimeException e) {
            log.warn("Suchindex nicht aktualisiert", e);
        }
    }

    /** @return wie viele Notizen abgeholt wurden */
    int runOnce() {
        var claimed = index.claim(BATCH);
        for (var queued : claimed) {
            try {
                var updates = snapshots.current(queued.noteId());
                if (updates.isEmpty()) {
                    continue;
                }
                if (updates.stream().anyMatch(UpdateRecord::ciphertext)) {
                    index.remove(queued.noteId());
                    continue;
                }
                var text = yjs.get().textOf(updates.stream().map(UpdateRecord::payload).toList());
                index.upsert(queued.noteId(), queued.vaultId(), updates.getLast().serverSequence(), text);
            } catch (RuntimeException e) {
                log.warn("Notiz {} nicht indiziert, neuer Versuch spaeter", queued.noteId().value(), e);
                index.requeue(queued.noteId());
            }
        }
        return claimed.size();
    }
}
