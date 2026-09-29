package de.tstieh.stoneintelligence.platform.sync.relay;

import de.tstieh.stoneintelligence.domain.id.NoteId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gemeinsamer Vertrag fuer Fake- und Postgres-Implementierung. */
public abstract class SnapshotStoreContractTest {

    protected abstract SnapshotStore store();

    /** Liefert eine existierende Notiz (Postgres braucht die Fremdschluessel-Zeile). */
    protected abstract NoteId newNote();

    private SnapshotStore snapshots;
    private NoteId noteId;

    @BeforeEach
    void setUp() {
        snapshots = store();
        noteId = newNote();
    }

    @Test
    void should_rememberWhoWroteEachUpdate_andWhen() {
        snapshots.append(noteId, new byte[] {1}, false, "tom");
        snapshots.appendIfCurrent(noteId, 1, new byte[] {2}, "ki:Assistent");
        snapshots.append(noteId, new byte[] {3}, false, null);

        var log = snapshots.log(noteId);

        assertThat(log).extracting(UpdateInfo::serverSequence).containsExactly(1L, 2L, 3L);
        assertThat(log).extracting(UpdateInfo::actor).containsExactly("tom", "ki:Assistent", null);
        assertThat(log).allSatisfy(info -> assertThat(info.createdAt()).isNotNull());
        assertThat(log.get(0).createdAt()).isBeforeOrEqualTo(log.get(2).createdAt());
        assertThat(snapshots.log(newNote())).isEmpty();
    }

    @Test
    void should_listTheUpdatesUpToARevision() {
        snapshots.append(noteId, new byte[] {1}, false, "tom");
        snapshots.append(noteId, new byte[] {2}, false, "tom");
        snapshots.append(noteId, new byte[] {3}, false, "tom");

        assertThat(snapshots.listUpTo(noteId, 2)).extracting(UpdateRecord::serverSequence).containsExactly(1L, 2L);
        assertThat(snapshots.listUpTo(noteId, 0)).isEmpty();
    }

    @Test
    void should_markEncryptedUpdatesInTheLog() {
        snapshots.append(noteId, new byte[] {1}, true, "tom");

        assertThat(snapshots.log(noteId)).extracting(UpdateInfo::ciphertext).containsExactly(true);
    }
}
