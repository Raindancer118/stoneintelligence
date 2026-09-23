package de.raindancer118.stoneintelligence.platform.ai;

import java.util.List;
import java.util.Set;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiServiceTest {

    @Test
    void should_processOnlyItsLevels_andWriteAsItsAgentIdentity() {
        var service = new AiService("gemini", "Gemini", Set.of(1, 2));

        assertThat(service.mayProcess(NoteLevel.of(2))).isTrue();
        assertThat(service.mayProcess(NoteLevel.of(3))).isFalse();
        assertThat(service.agent()).isEqualTo("ki:Gemini");
    }

    @Test
    void should_neverAllowUnsyncedOrEncryptedLevels() {
        assertThatThrownBy(() -> new AiService("a", "A", Set.of(100))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiService("a", "A", Set.of(101))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_rejectIdsThatDoNotFitInUrlsOrConfig() {
        for (var id : List.of("", "Gross", "mit leer", "../x", "a".repeat(41))) {
            assertThatThrownBy(() -> new AiService(id, "A", Set.of(1)), id).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void should_requireUniqueIdsAndNames_becauseTheNameIsTheAgentIdentity() {
        var gemini = new AiService("gemini", "Gemini", Set.of(1));

        assertThatThrownBy(() -> new AiServiceDirectory(List.of(gemini, new AiService("gemini", "Anders", Set.of(1)))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiServiceDirectory(List.of(gemini, new AiService("zwei", "Gemini", Set.of(1)))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AiServiceDirectory(List.of(gemini)).find("gemini")).contains(gemini);
    }
}
