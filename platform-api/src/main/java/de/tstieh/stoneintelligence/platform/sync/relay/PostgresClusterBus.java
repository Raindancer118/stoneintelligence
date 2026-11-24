package de.tstieh.stoneintelligence.platform.sync.relay;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Instanzen sprechen ueber Postgres {@code LISTEN/NOTIFY} miteinander (ADR 0013, Schritt 7) - kein
 * zusaetzlicher Dienst. Eine Verbindung aus dem Pool bleibt dafuer dauerhaft belegt. NOTIFY in
 * einer Transaktion geht erst beim Commit raus, in derselben Reihenfolge, in der committet wurde.
 *
 * <p>Grenze: Waehrend die Lausch-Verbindung neu aufgebaut wird, gehen Nachrichten verloren. Geraete
 * holen Bestandsaenderungen ueber den Aenderungs-Feed nach; ein verpasstes Live-Update bekommt eine
 * offene Notiz beim naechsten Beitreten.
 */
public final class PostgresClusterBus implements ClusterBus, AutoCloseable {

    static final String CHANNEL = "stoneintelligence_cluster";
    /** NOTIFY nimmt hoechstens 8000 Bytes; etwas Luft fuer die Kodierung. */
    static final int MAX_PAYLOAD_BYTES = 7900;

    private static final Logger log = LoggerFactory.getLogger(PostgresClusterBus.class);

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final String instanceId = UUID.randomUUID().toString();
    private final List<Consumer<ClusterMessage>> listeners = new CopyOnWriteArrayList<>();
    private final Thread listener;
    private volatile boolean running = true;

    public PostgresClusterBus(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.listener = Thread.ofPlatform().name("cluster-listener").daemon().start(this::listen);
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public void publish(ClusterMessage message) {
        var payload = ClusterMessage.encode(instanceId, message);
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            // Nur Cursor koennen so gross werden; der naechste kommt gleich.
            log.debug("Cluster-Nachricht zu gross, nicht verteilt: {}", message.getClass().getSimpleName());
            return;
        }
        try {
            jdbc.sql("SELECT pg_notify(:channel, :payload)").param("channel", CHANNEL).param("payload", payload)
                .query().listOfRows();
        } catch (RuntimeException e) {
            // Die eigene Instanz hat schon zugestellt; die anderen holen nach (s. Klassendoc).
            log.warn("Cluster-Nachricht nicht verteilt", e);
        }
    }

    @Override
    public void subscribe(Consumer<ClusterMessage> listener) {
        listeners.add(listener);
    }

    private void listen() {
        long backoffMillis = 500;
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                try (var statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                var pg = connection.unwrap(PGConnection.class);
                backoffMillis = 500;
                while (running) {
                    var notifications = pg.getNotifications(1000);
                    if (notifications == null) {
                        continue;
                    }
                    for (var notification : notifications) {
                        dispatch(notification.getParameter());
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("Cluster-Verbindung unterbrochen, neuer Versuch in {} ms", backoffMillis, e);
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException interrupted) {
                    return;
                }
                backoffMillis = Math.min(backoffMillis * 2, 30_000);
            }
        }
    }

    void dispatch(String wire) {
        ClusterMessage.Envelope envelope;
        try {
            envelope = ClusterMessage.decode(wire);
        } catch (RuntimeException e) {
            log.warn("Unlesbare Cluster-Nachricht verworfen");
            return;
        }
        if (envelope.origin().equals(instanceId)) {
            return;
        }
        for (var listener : listeners) {
            try {
                listener.accept(envelope.message());
            } catch (RuntimeException e) {
                log.warn("Cluster-Nachricht nicht verarbeitet", e);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        listener.interrupt();
    }
}
