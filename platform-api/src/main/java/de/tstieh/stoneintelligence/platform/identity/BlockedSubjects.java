package de.tstieh.stoneintelligence.platform.identity;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Per SCIM gesperrte Konten (ADR 0013), im Speicher fuer die Pruefung jeder Anfrage. Aenderungen ueber
 * diese Instanz wirken nach dem Commit sofort, ueber andere nach hoechstens {@link #REFRESH_MILLIS}.
 * Wer neu gesperrt wird, loest {@link SubjectBlocked} aus - auf jeder Instanz, die es bemerkt.
 */
public class BlockedSubjects {

    static final long REFRESH_MILLIS = 5_000;

    private final Supplier<Set<String>> source;
    private final ApplicationEventPublisher events;
    private volatile Set<String> blocked;

    public BlockedSubjects(Supplier<Set<String>> source, ApplicationEventPublisher events) {
        this.source = source;
        this.events = events;
    }

    public boolean isBlocked(String subject) {
        var current = blocked;
        if (current == null) {
            refresh();
            current = blocked;
        }
        return current.contains(subject);
    }

    /** Nach einer Aenderung ueber diese Instanz - innerhalb einer Transaktion erst nach dem Commit. */
    public void changed() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh();
                }
            });
        } else {
            refresh();
        }
    }

    @Scheduled(initialDelay = REFRESH_MILLIS, fixedDelay = REFRESH_MILLIS)
    public synchronized void refresh() {
        var previous = blocked;
        var fresh = Set.copyOf(source.get());
        blocked = fresh;
        if (previous == null) {
            return;
        }
        var newly = new HashSet<>(fresh);
        newly.removeAll(previous);
        newly.forEach(subject -> events.publishEvent(new SubjectBlocked(subject)));
    }
}
