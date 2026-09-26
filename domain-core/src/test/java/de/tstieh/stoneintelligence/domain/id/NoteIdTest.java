package de.tstieh.stoneintelligence.domain.id;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoteIdTest {

    @Test
    void should_generateUniqueIds_when_calledRepeatedly() {
        assertThat(NoteId.newId()).isNotEqualTo(NoteId.newId());
    }

    @Test
    void should_roundtripThroughString_when_parsedBack() {
        var id = NoteId.newId();

        assertThat(NoteId.of(id.value().toString())).isEqualTo(id);
    }

    @Test
    void should_rejectMalformedInput_when_stringIsNotAUuid() {
        assertThatThrownBy(() -> NoteId.of("../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_rejectNullUuid_when_constructedDirectly() {
        assertThatThrownBy(() -> new NoteId(null))
            .isInstanceOf(NullPointerException.class);
    }
}
