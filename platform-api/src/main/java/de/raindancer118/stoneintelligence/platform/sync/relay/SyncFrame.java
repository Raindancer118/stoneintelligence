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
