package de.raindancer118.stoneintelligence.platform.sync.relay;

/**
 * Schmale Abstraktion ueber eine WebSocket-Verbindung, damit die Relay-Logik ohne
 * Spring-WebSocket-Infrastruktur testbar ist. {@link SyncWebSocketHandler} ist der einzige
 * produktive Adapter.
 */
public interface SyncSession {

    String id();

    /** Ein persistiertes Yjs-Dokument-Update (wird als Snapshot gespeichert, bevor es rausgeht). */
    void sendDocUpdate(byte[] payload);

    /**
     * Eine ephemere Awareness-/Cursor-Nachricht (Yjs Awareness-Protokoll) - wird NIEMALS
     * persistiert, nur an aktuell verbundene Sessions weitergereicht. Vermischen mit dem
     * Dokument-Update-Log wuerde dessen Reihenfolge/Semantik korrumpieren.
     */
    void sendAwarenessUpdate(byte[] payload);

    void close(int code, String reason);
}
