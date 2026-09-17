package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.nio.charset.StandardCharsets;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-Framing fuer die multiplexte WebSocket-Verbindung (eine Verbindung, beliebig viele
 * Notiz-"Raeume"): {@code [1 Byte Nachrichtentyp][36 Byte NoteId als ASCII-UUID][Rest: Payload]}.
 * Bewusst als reiner, Spring-freier Typ - {@link SyncWebSocketHandler} ist der einzige
 * produktive Adapter, der ihn nutzt.
 */
class SyncFrameTest {

    private final NoteId noteId = NoteId.newId();

    @Test
    void should_roundTrip_docUpdateFrame() {
        var payload = "hello".getBytes(StandardCharsets.UTF_8);
        var frame = new SyncFrame(SyncFrame.TYPE_DOC_UPDATE, noteId, payload);

        var decoded = SyncFrame.decode(frame.encode());

        assertThat(decoded.messageType()).isEqualTo(SyncFrame.TYPE_DOC_UPDATE);
        assertThat(decoded.noteId()).isEqualTo(noteId);
        assertThat(decoded.payload()).isEqualTo(payload);
    }

    @Test
    void should_roundTrip_joinFrame_withEmptyPayload() {
        var frame = new SyncFrame(SyncFrame.TYPE_JOIN, noteId, new byte[0]);

        var decoded = SyncFrame.decode(frame.encode());

        assertThat(decoded.messageType()).isEqualTo(SyncFrame.TYPE_JOIN);
        assertThat(decoded.noteId()).isEqualTo(noteId);
        assertThat(decoded.payload()).isEmpty();
    }

    @Test
    void should_preserveDifferentNoteIds_distinctly() {
        var otherNoteId = NoteId.newId();

        var decodedFirst = SyncFrame.decode(new SyncFrame(SyncFrame.TYPE_AWARENESS, noteId, new byte[] {1}).encode());
        var decodedSecond = SyncFrame.decode(
            new SyncFrame(SyncFrame.TYPE_AWARENESS, otherNoteId, new byte[] {2}).encode());

        assertThat(decodedFirst.noteId()).isEqualTo(noteId);
        assertThat(decodedSecond.noteId()).isEqualTo(otherNoteId);
        assertThat(decodedFirst.noteId()).isNotEqualTo(decodedSecond.noteId());
    }

    @Test
    void should_throw_when_frameShorterThanTypeBytePlusNoteId() {
        assertThatThrownBy(() -> SyncFrame.decode(new byte[10])).isInstanceOf(IllegalArgumentException.class);
    }
}
