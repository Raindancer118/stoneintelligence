package de.tstieh.stoneintelligence.platform.ai;

import java.util.List;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
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

    // Konfiguration ueber eine Umgebungsvariable: "id|Name|Levels; id|Name|Levels".
    @Test
    void should_readTheConfiguredServices_fromOneSetting() {
        var directory = AiServiceDirectory.parse(" gemini|Gemini|1 ; lokal|Ollama lokal|1,2 ;");

        assertThat(directory.all()).containsExactly(new AiService("gemini", "Gemini", Set.of(1)),
            new AiService("lokal", "Ollama lokal", Set.of(1, 2)));
        assertThat(AiServiceDirectory.parse("").all()).isEmpty();
        assertThat(AiServiceDirectory.parse(null).all()).isEmpty();
    }

    @Test
    void should_refuseToStart_withABrokenServiceConfiguration() {
        for (var broken : List.of("gemini|Gemini", "gemini|Gemini|x", "gemini|Gemini|101", "|Gemini|1")) {
            assertThatThrownBy(() -> AiServiceDirectory.parse(broken), broken).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
