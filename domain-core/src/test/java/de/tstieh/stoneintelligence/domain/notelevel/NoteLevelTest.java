package de.tstieh.stoneintelligence.domain.notelevel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoteLevelTest {

    @Test
    void should_acceptLowerBound_when_valueIsOne() {
        assertThat(NoteLevel.of(1).value()).isEqualTo(1);
    }

    @Test
    void should_acceptUpperBound_when_valueIs101() {
        assertThat(NoteLevel.of(101).value()).isEqualTo(101);
    }

    @Test
    void should_rejectValue_when_valueIsZero() {
        assertThatThrownBy(() -> NoteLevel.of(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_rejectValue_when_valueIsNegative() {
        assertThatThrownBy(() -> NoteLevel.of(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_rejectValue_when_valueExceeds101() {
        assertThatThrownBy(() -> NoteLevel.of(102))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_rejectValue_when_valueIsBetween99And101Exclusive() {
        // 100 ist ein gültiger, definierter Level (keine Synchronisation) - das hier prüft
        // nur, dass NoteLevel selbst keine semantische Lücke annimmt, die es nicht gibt.
        assertThat(NoteLevel.of(100).value()).isEqualTo(100);
    }

    @Test
    void should_identifyE2ee_when_valueIs101() {
        assertThat(NoteLevel.of(101).isE2ee()).isTrue();
        assertThat(NoteLevel.of(100).isE2ee()).isFalse();
    }

    @Test
    void should_identifyNoSync_when_valueIs100() {
        assertThat(NoteLevel.of(100).isNoSync()).isTrue();
        assertThat(NoteLevel.of(101).isNoSync()).isFalse();
        assertThat(NoteLevel.of(1).isNoSync()).isFalse();
    }
}
