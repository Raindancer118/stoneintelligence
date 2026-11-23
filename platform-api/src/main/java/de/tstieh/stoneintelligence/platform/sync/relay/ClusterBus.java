package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.function.Consumer;

/**
 * Verbindung zu den anderen platform-api-Instanzen (ADR 0013, Schritt 7). Eigene Nachrichten
 * kommen nie zurueck. Zustellung nach bestem Bemuehen: Wer eine Nachricht verpasst, holt den Stand
 * ueber den Aenderungs-Feed bzw. beim naechsten Beitreten.
 */
public interface ClusterBus {

    void publish(ClusterMessage message);

    void subscribe(Consumer<ClusterMessage> listener);

    /** Ob es andere Instanzen geben kann - sonst darf man sich Arbeit sparen, die nur ihnen nuetzt. */
    default boolean hasPeers() {
        return true;
    }

    /** Eindeutig je laufendem Prozess. */
    default String instanceId() {
        return "local";
    }

    /** Eine einzelne Instanz: niemand zum Benachrichtigen. */
    static ClusterBus single() {
        return new ClusterBus() {
            @Override
            public void publish(ClusterMessage message) {
            }

            @Override
            public void subscribe(Consumer<ClusterMessage> listener) {
            }

            @Override
            public boolean hasPeers() {
                return false;
            }
        };
    }
}
