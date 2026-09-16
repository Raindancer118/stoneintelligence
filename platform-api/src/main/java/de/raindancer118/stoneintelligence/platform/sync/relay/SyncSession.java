package de.raindancer118.stoneintelligence.platform.sync.relay;

/**
 * Schmale Abstraktion ueber eine WebSocket-Verbindung, damit die Relay-Logik ohne
 * Spring-WebSocket-Infrastruktur testbar ist. {@link SyncWebSocketHandler} ist der einzige
 * produktive Adapter.
 */
public interface SyncSession {

    String id();

    void sendUpdate(byte[] payload);

    void close(int code, String reason);
}
