package de.raindancer118.stoneintelligence.worker.ingest;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceModelsTest {

    // Ohne eigene Angaben nimmt ein Dienst die StoneAI-Standardketten ueber ai-gateway.
    @Test
    void should_useTheGatewayDefaults_forAnUnconfiguredService() {
        var models = ServiceModels.from(Map.of());

        var gemini = models.forService("gemini");

        assertThat(gemini.fastChain()).isNotEmpty();
        assertThat(gemini.endpoint()).isNull();
    }

    @Test
    void should_readChainsAndACustomEndpoint_perService() {
        var models = ServiceModels.from(Map.of(
            "AI_SERVICE_OLLAMA_LOKAL_ENDPOINT", "http://ollama:11434/v1",
            "AI_SERVICE_OLLAMA_LOKAL_FAST", "ollama-lokal:llama3.1:8b",
            "AI_SERVICE_OLLAMA_LOKAL_SMART", "ollama-lokal:llama3.1:70b, groq:llama-3.3-70b-versatile",
            "AI_SERVICE_OLLAMA_LOKAL_KEYS", ""));

        var lokal = models.forService("ollama-lokal");

        assertThat(lokal.endpoint()).isEqualTo("http://ollama:11434/v1");
        assertThat(lokal.fastChain()).containsExactly("ollama-lokal:llama3.1:8b");
        assertThat(lokal.smartChain()).containsExactly("ollama-lokal:llama3.1:70b", "groq:llama-3.3-70b-versatile");
        assertThat(lokal.keys()).isEmpty();
    }

    @Test
    void should_refuseAnEndpointThatIsNoHttpUrl() {
        assertThatThrownBy(() -> ServiceModels.from(Map.of("AI_SERVICE_X_ENDPOINT", "file:///etc")).forService("x"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
