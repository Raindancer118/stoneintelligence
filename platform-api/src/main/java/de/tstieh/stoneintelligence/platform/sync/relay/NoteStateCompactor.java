package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.platform.sync.yjs.YjsBridge;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fasst lange Historien zu einem Zustand zusammen (ADR 0013, Schritt 6). Ausgeloest von einem
 * Beitritt, der mehr als {@code threshold} Updates bekam; laeuft danach im Hintergrund, damit der
 * Beitritt selbst nicht wartet. Verschluesselte Updates fasst der Server nie an.
 */
@Component
public class NoteStateCompactor {

    private static final Logger log = LoggerFactory.getLogger(NoteStateCompactor.class);

    private final SnapshotStore store;
    private final Supplier<YjsBridge> yjs;
    private final int threshold;
    private final Executor executor;
    private final Set<NoteId> running = ConcurrentHashMap.newKeySet();

    @Autowired
    public NoteStateCompactor(SnapshotStore store, ObjectProvider<YjsBridge> yjs,
                              @Value("${stoneintelligence.sync.compaction-threshold:200}") int threshold) {
        this(store, yjs::getObject, threshold, new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10_000), runnable -> Thread.ofPlatform().name("note-compaction").daemon().unstarted(runnable)));
    }

    NoteStateCompactor(SnapshotStore store, Supplier<YjsBridge> yjs, int threshold, Executor executor) {
        this.store = store;
        this.yjs = yjs;
        this.threshold = threshold;
        this.executor = executor;
    }

    /** {@code sent}: was der Beitretende bekommen hat ({@link SnapshotStore#current}). */
    public void joined(NoteId noteId, List<UpdateRecord> sent) {
        if (sent.size() <= threshold || sent.stream().anyMatch(UpdateRecord::ciphertext) || !running.add(noteId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    compact(noteId);
                } catch (RuntimeException e) {
                    // Nur ein Cache - der naechste Beitritt versucht es erneut.
                    log.warn("Zusammenfassen der Notiz {} fehlgeschlagen", noteId.value(), e);
                } finally {
                    running.remove(noteId);
                }
            });
        } catch (RejectedExecutionException full) {
            running.remove(noteId);
        }
    }

    private void compact(NoteId noteId) {
        var updates = store.current(noteId);
        if (updates.size() <= threshold || updates.stream().anyMatch(UpdateRecord::ciphertext)) {
            return;
        }
        var merged = yjs.get().merge(updates.stream().map(UpdateRecord::payload).toList());
        store.saveState(noteId, updates.getLast().serverSequence(), merged);
    }

    @PreDestroy
    void stop() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }
}
