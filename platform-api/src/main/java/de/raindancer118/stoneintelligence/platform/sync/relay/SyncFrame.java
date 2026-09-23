package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import de.raindancer118.stoneintelligence.domain.id.NoteId;

/**
 * Wire-Framing der multiplexten Sync-Verbindung: EINE WebSocket-Verbindung joint/verlaesst
 * beliebig viele Notiz-"Raeume" (statt frueher eine Verbindung pro Notiz), jede Nachricht traegt
 * deshalb ihre {@link NoteId} mit.
 *
 * <p>Layout: {@code [1 Byte Nachrichtentyp][36 Byte NoteId als ASCII-UUID-String][Rest: Payload]}.
 * Die NoteId ist bewusst fest 36 Bytes (Standard-UUID-Stringform, z. B.
 * {@code 3fa85f64-5717-4562-b3fc-2c963f66afa6}) statt laengenpraefixiert - kein Parsing-Fallstrick,
 * einfach zu implementieren auf beiden Seiten (Server/TypeScript-Client).
 */
public record SyncFrame(byte messageType, NoteId noteId, byte[] payload) {

    public static final byte TYPE_DOC_UPDATE = 0;
    public static final byte TYPE_AWARENESS = 1;
    public static final byte TYPE_JOIN = 2;
    public static final byte TYPE_LEAVE = 3;
    /**
     * Server->Client: genau diese Notiz wurde geloescht. Betrifft NUR diesen einen Notiz-Raum,
     * nicht die gesamte (ggf. mit anderen Notizen geteilte) Verbindung - s. {@link SyncSession#notifyNoteDeleted}.
     */
    public static final byte TYPE_NOTE_DELETED = 4;
    /**
     * Server->Client: die komplette Late-Joiner-Update-Historie fuer diese Notiz wurde gesendet.
     * Ohne dieses explizite Signal musste der Client raten, wann "genug" Zeit fuer den Catchup
     * vergangen ist (fixe Gnadenfrist) - unter Last (viele/grosse Notizen, gestaffelte Joins auf
     * derselben geteilten Verbindung) reichte diese Frist nachweislich nicht, der Client spielte
     * dann lokalen Inhalt zusaetzlich zum inzwischen doch noch eingetroffenen Server-Inhalt ein
     * (live beobachtet: verdreifachter Notizinhalt nach mehreren Reconnect-Zyklen).
     */
    public static final byte TYPE_CATCHUP_COMPLETE = 5;

    /**
     * Server->Client, vault-weit: unter diesem Pfad wurde eine Notiz angelegt / geloescht /
     * umbenannt (Payload = der Pfad als UTF-8, bei RENAMED der NEUE Pfad). Gehen an ALLE
     * Verbindungen des Vaults, nicht nur an die eines Notiz-Raums - genau das ist ihr Zweck: seit
     * das Plugin nur noch geoeffnete Notizen joint, erreicht eine notenskopierte Nachricht die
     * anderen Geraete nicht mehr (s. {@link VaultAnnouncementService}). Das Wire-Layout bleibt
     * unveraendert, es kommen nur neue Nachrichtentypen hinzu.
     */
    public static final byte TYPE_VAULT_NOTE_CREATED = 6;
    public static final byte TYPE_VAULT_NOTE_DELETED = 7;
    public static final byte TYPE_VAULT_NOTE_RENAMED = 8;
    /**
     * Inhalt einer Notiz hat sich geaendert (Payload: Pfad). Nur an Verbindungen, die die Notiz
     * NICHT gejoint haben - die bekommen das Update ohnehin selbst. Gedrosselt, s.
     * {@code VaultAnnouncementService#announceNoteUpdated}.
     */
    public static final byte TYPE_VAULT_NOTE_UPDATED = 9;
    /**
     * Client->Server (NoteId-Feld ohne Bedeutung): diese Verbindung versteht
     * {@link #TYPE_VAULT_NOTE_UPDATED}. Opt-in, damit aeltere Plugins, die jeden unbekannten Typ
     * als Yjs-Update behandeln, ihn nie bekommen.
     */
    public static final byte TYPE_SUBSCRIBE_CONTENT_UPDATES = 10;
    /**
     * Client->Server (NoteId-Feld ohne Bedeutung): diese Verbindung versteht
     * {@link #TYPE_VAULT_FOLDERS_CHANGED}. Opt-in aus demselben Grund wie Typ 10.
     */
    public static final byte TYPE_SUBSCRIBE_FOLDER_EVENTS = 11;
    /**
     * Server->Client: Ordner unter diesem Pfad angelegt, geloescht oder verschoben (Payload: der
     * Pfad, NoteId-Feld = {@link #NO_NOTE}). Der Client holt daraufhin die Ordnerliste.
     */
    public static final byte TYPE_VAULT_FOLDERS_CHANGED = 12;
    /**
     * Client->Server (NoteId-Feld ohne Bedeutung): diese Verbindung kennt Dateien (ADR 0009) und
     * bekommt deren Anlage/Loeschung/Umbenennung/Aenderung in den Frames 6-9. Aeltere Plugins
     * wuerden eine Datei sonst als leere Notiz anlegen.
     */
    public static final byte TYPE_SUBSCRIBE_FILE_EVENTS = 13;
    /** Platzhalter im NoteId-Feld fuer Nachrichten, die keine Notiz betreffen. */
    public static final de.raindancer118.stoneintelligence.domain.id.NoteId NO_NOTE =
        de.raindancer118.stoneintelligence.domain.id.NoteId.of(new java.util.UUID(0, 0));

    public static final int NOTE_ID_LENGTH = 36;
    private static final int HEADER_LENGTH = 1 + NOTE_ID_LENGTH;

    public static SyncFrame decode(byte[] raw) {
        if (raw.length < HEADER_LENGTH) {
            throw new IllegalArgumentException("frame too short: " + raw.length + " bytes, need at least " + HEADER_LENGTH);
        }
        var messageType = raw[0];
        var noteIdString = new String(raw, 1, NOTE_ID_LENGTH, StandardCharsets.US_ASCII);
        var payload = Arrays.copyOfRange(raw, HEADER_LENGTH, raw.length);
        return new SyncFrame(messageType, NoteId.of(noteIdString), payload);
    }

    public byte[] encode() {
        var noteIdBytes = noteId.value().toString().getBytes(StandardCharsets.US_ASCII);
        var framed = ByteBuffer.allocate(1 + noteIdBytes.length + payload.length);
        framed.put(messageType);
        framed.put(noteIdBytes);
        framed.put(payload);
        return framed.array();
    }
}
