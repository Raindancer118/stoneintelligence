package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import de.tstieh.stoneintelligence.domain.id.NoteId;

/**
 * Testdouble, das gezielt EINE bestimmte Verschraenkung zweier Threads erzwingt (statt auf eine
 * zufaellige Race-Window-Kollision zu hoffen, was den Test flaky UND ungeeignet als Regression
 * machen wuerde, s. Diary-Feedback "concurrency test that never fails is cover, not a test"):
 * {@link #listSince} signalisiert per Latch, dass es betreten wurde, und blockiert dann, bis der
 * Test explizit freigibt - so laesst sich der Ziel-Interleaving-Punkt aus dem P1-Befund
 * "Catchup-complete kann ein paralleles Live-Update ueberholen" deterministisch nachstellen.
 */
final class InterceptingSnapshotStore implements SnapshotStore {

    private final SnapshotStore delegate;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);

    InterceptingSnapshotStore(SnapshotStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public UpdateRecord append(NoteId noteId, byte[] payload, boolean ciphertext) {
        return delegate.append(noteId, payload, ciphertext);
    }

    @Override
    public java.util.Optional<UpdateRecord> appendIfCurrent(NoteId noteId, long expectedRevision, byte[] payload) {
        return delegate.appendIfCurrent(noteId, expectedRevision, payload);
    }

    @Override
    public List<UpdateRecord> listSince(NoteId noteId, long afterServerSequence) {
        entered.countDown();
        await(proceed);
        return delegate.listSince(noteId, afterServerSequence);
    }

    @Override
    public java.util.Map<NoteId, Long> latestRevisions(java.util.Collection<NoteId> noteIds) {
        return delegate.latestRevisions(noteIds);
    }

    /** Blockiert den Aufrufer bis {@link #entered} feuert (spaetestens jedoch mit Timeout, gegen ein haengendes CI). */
    void awaitEnteredListSince() {
        await(entered);
    }

    void releaseListSince() {
        proceed.countDown();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for latch - test interleaving broke");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
