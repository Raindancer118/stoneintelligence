package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;

/** Liest wie die Datenbank bei einem Ausfall: jede Leseanfrage scheitert. */
public final class InterceptingSnapshotStoreFailing implements SnapshotStore {

    private final SnapshotStore delegate;

    public InterceptingSnapshotStoreFailing(SnapshotStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public UpdateRecord append(NoteId noteId, byte[] payload, boolean ciphertext, String actor) {
        return delegate.append(noteId, payload, ciphertext, actor);
    }

    @Override
    public java.util.Optional<UpdateRecord> appendIfCurrent(NoteId noteId, long expectedRevision, byte[] payload, String actor) {
        return delegate.appendIfCurrent(noteId, expectedRevision, payload, actor);
    }

    @Override
    public List<UpdateRecord> listSince(NoteId noteId, long afterServerSequence) {
        throw new org.springframework.dao.DataAccessResourceFailureException("Datenbank nicht erreichbar");
    }

    @Override
    public List<UpdateRecord> listUpTo(NoteId noteId, long serverSequence) {
        throw new org.springframework.dao.DataAccessResourceFailureException("Datenbank nicht erreichbar");
    }

    @Override
    public List<UpdateInfo> log(NoteId noteId) {
        return delegate.log(noteId);
    }

    @Override
    public java.util.Map<NoteId, Long> latestRevisions(java.util.Collection<NoteId> noteIds) {
        return delegate.latestRevisions(noteIds);
    }

    @Override
    public java.util.Optional<NoteState> state(NoteId noteId) {
        throw new org.springframework.dao.DataAccessResourceFailureException("Datenbank nicht erreichbar");
    }

    @Override
    public void saveState(NoteId noteId, long revision, byte[] state) {
        delegate.saveState(noteId, revision, state);
    }
}
