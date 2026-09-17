package de.raindancer118.stoneintelligence.platform.sync.relay;

import de.raindancer118.stoneintelligence.domain.id.NoteId;

/**
 * Schmale Abstraktion ueber eine WebSocket-Verbindung, damit die Relay-Logik ohne
 * Spring-WebSocket-Infrastruktur testbar ist. {@link SyncWebSocketHandler} ist der einzige
 * produktive Adapter.
 *
 * <p>Eine Verbindung ist seit der Multiplexing-Umstellung nicht mehr an genau EINE Notiz
 * gebunden - sie kann beliebig viele Notiz-"Raeume" gleichzeitig joinen (s.
 * {@code SyncWebSocketHandler}). Jede gesendete Nachricht traegt deshalb die {@link NoteId}
 * mit, fuer die sie bestimmt ist, statt sie implizit aus der Session abzuleiten.
 */
public interface SyncSession {

    String id();

    /** Ein persistiertes Yjs-Dokument-Update (wird als Snapshot gespeichert, bevor es rausgeht). */
    void sendDocUpdate(NoteId noteId, byte[] payload);

    /**
     * Eine ephemere Awareness-/Cursor-Nachricht (Yjs Awareness-Protokoll) - wird NIEMALS
     * persistiert, nur an aktuell verbundene Sessions weitergereicht. Vermischen mit dem
     * Dokument-Update-Log wuerde dessen Reihenfolge/Semantik korrumpieren.
     */
    void sendAwarenessUpdate(NoteId noteId, byte[] payload);

    /**
     * Die Notiz wurde geloescht - NUR dieser eine Notiz-"Raum" endet, die Verbindung selbst
     * bleibt fuer alle anderen gejointen Notizen bestehen. Bewusst KEINE {@code close(...)} der
     * gesamten Session (das war ein echter Bug: vor der Multiplexing-Umstellung war eine
     * Verbindung = eine Notiz, seitdem teilen sich beliebig viele Notizen dieselbe Verbindung -
     * die Loeschung EINER Notiz haette sonst den Sync ALLER anderen mitgerissen).
     */
    void notifyNoteDeleted(NoteId noteId);

    void close(int code, String reason);
}
