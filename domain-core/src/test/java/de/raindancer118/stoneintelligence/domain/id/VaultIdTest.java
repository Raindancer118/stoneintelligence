package de.raindancer118.stoneintelligence.domain.id;

import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultIdTest {

    @Nested
    class NewId {

        @Test
        void should_generateUniqueIds_when_calledRepeatedly() {
            var first = VaultId.newId();
            var second = VaultId.newId();

            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    class Of {

        @Test
        void should_wrapGivenUuid_when_parsableStringGiven() {
            var uuid = UUID.randomUUID();

            var id = VaultId.of(uuid.toString());

            assertThat(id.value()).isEqualTo(uuid);
        }

        @Test
        void should_rejectNull_when_stringIsNull() {
            assertThatThrownBy(() -> VaultId.of((String) null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        void should_rejectMalformedInput_when_stringIsNotAUuid() {
            assertThatThrownBy(() -> VaultId.of("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class EqualityAndIdentity {

        @Test
        void should_beEqual_when_sameUnderlyingUuid() {
            var uuid = UUID.randomUUID();

            assertThat(VaultId.of(uuid)).isEqualTo(VaultId.of(uuid.toString()));
        }

        @Test
        void should_notEqualNoteId_when_sameUnderlyingUuidButDifferentIdType() {
            var uuid = UUID.randomUUID();

            EntityId vaultId = VaultId.of(uuid);
            EntityId noteId = NoteId.of(uuid);

            assertThat(vaultId).isNotEqualTo(noteId);
        }

        @Test
        void should_renderUuidInToString_when_calledForLogging() {
            var uuid = UUID.randomUUID();

            assertThat(VaultId.of(uuid).toString()).contains(uuid.toString());
        }
    }
}
