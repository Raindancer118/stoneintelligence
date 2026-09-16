package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.nio.charset.StandardCharsets;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncRelayServiceTest {

    private final FakeSnapshotStore snapshotStore = new FakeSnapshotStore();
    private final SyncRoomRegistry registry = new SyncRoomRegistry();
    private final SyncRelayService relay = new SyncRelayService(snapshotStore, registry);
    private final NoteId noteId = NoteId.newId();

    private byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    class Broadcast {

        @Test
        void should_deliverUpdateToOtherSessions_butNotBackToSender_when_onUpdateCalled() {
            var sender = new RecordingSyncSession("sender");
            var other = new RecordingSyncSession("other");
            relay.onJoin(noteId, sender);
            relay.onJoin(noteId, other);

            relay.onUpdate(noteId, sender, bytes("update-1"), false);

            assertThat(other.received).containsExactly(bytes("update-1"));
            assertThat(sender.received).isEmpty();
        }

        @Test
        void should_notLeakUpdatesToOtherNotesRooms_when_broadcasting() {
            var otherNoteId = NoteId.newId();
            var sessionOnThisNote = new RecordingSyncSession("a");
            var sessionOnOtherNote = new RecordingSyncSession("b");
            relay.onJoin(noteId, sessionOnThisNote);
            relay.onJoin(otherNoteId, sessionOnOtherNote);

            relay.onUpdate(noteId, sessionOnThisNote, bytes("update"), false);

            assertThat(sessionOnOtherNote.received).isEmpty();
        }
    }

    @Nested
    class LateJoinerCatchup {

        @Test
        void should_receiveFullUpdateHistory_when_joiningAfterUpdatesAlreadyHappened() {
            var early = new RecordingSyncSession("early");
            relay.onJoin(noteId, early);
            relay.onUpdate(noteId, early, bytes("update-1"), false);
            relay.onUpdate(noteId, early, bytes("update-2"), false);

            var lateJoiner = new RecordingSyncSession("late");
            relay.onJoin(noteId, lateJoiner);

            assertThat(lateJoiner.received).containsExactly(bytes("update-1"), bytes("update-2"));
        }
    }

    @Nested
    class Leave {

        @Test
        void should_stopReceivingUpdates_when_sessionHasLeft() {
            var sender = new RecordingSyncSession("sender");
            var leaver = new RecordingSyncSession("leaver");
            relay.onJoin(noteId, sender);
            relay.onJoin(noteId, leaver);
            relay.onLeave(noteId, leaver);

            relay.onUpdate(noteId, sender, bytes("update"), false);

            assertThat(leaver.received).isEmpty();
        }
    }

    @Nested
    class NoteDeleted {

        @Test
        void should_closeAllConnectedSessionsWithNoteDeletedCode_when_noteWasDeleted() {
            var sessionA = new RecordingSyncSession("a");
            var sessionB = new RecordingSyncSession("b");
            relay.onJoin(noteId, sessionA);
            relay.onJoin(noteId, sessionB);

            relay.onNoteDeleted(noteId);

            assertThat(sessionA.closedWithCode).isEqualTo(SyncRelayService.CLOSE_CODE_NOTE_DELETED);
            assertThat(sessionB.closedWithCode).isEqualTo(SyncRelayService.CLOSE_CODE_NOTE_DELETED);
        }
    }
}
