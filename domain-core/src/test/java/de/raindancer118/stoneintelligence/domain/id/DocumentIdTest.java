package de.raindancer118.stoneintelligence.domain.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentIdTest {

    @Test
    void should_generateUniqueIds_when_calledRepeatedly() {
        assertThat(DocumentId.newId()).isNotEqualTo(DocumentId.newId());
    }

    @Test
    void should_roundtripThroughString_when_parsedBack() {
        var id = DocumentId.newId();

        assertThat(DocumentId.of(id.value().toString())).isEqualTo(id);
    }

    @Test
    void should_rejectMalformedInput_when_stringIsNotAUuid() {
        assertThatThrownBy(() -> DocumentId.of("'; DROP TABLE documents; --"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
