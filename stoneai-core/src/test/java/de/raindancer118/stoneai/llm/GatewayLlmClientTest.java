package de.raindancer118.stoneai.llm;

import de.raindancer118.stoneai.config.ConfigSchema;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.extract.LlmAnswer;
import de.raindancer118.stoneai.extract.Tier;
import io.github.raindancer118.aigateway.AiProvider;
import io.github.raindancer118.aigateway.ChatRequest;
import io.github.raindancer118.aigateway.ChatResponse;
import io.github.raindancer118.aigateway.Usage;
import io.github.raindancer118.revolver.ChamberStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayLlmClientTest {

    /** Answers with its own name and the model it was asked for. */
    private static final class EchoProvider implements AiProvider {
        private final String name;
        final List<String> models = new ArrayList<>();

        EchoProvider(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ChatResponse chat(String model, ChatRequest request) {
            models.add(model);
            return new ChatResponse("antwort von " + name, name, model, new Usage(1, 2, 3));
        }

        @Override
        public double availability() {
            return 1.0;
        }

        @Override
        public List<ChamberStats> keyPoolStatus() {
            return List.of();
        }
    }

    // StoneIntelligence baut die Provider selbst (je KI-Dienst, auch beliebige OpenAI-kompatible
    // Endpunkte) - die Modellketten der Config bleiben dieselben.
    @Test
    @DisplayName("should route through providers the caller built, by the configured chains")
    void should_useProvidedProviders() {
        StoneAiConfig config = StoneAiConfig.defaults();
        ConfigSchema.byPath("llm.fastChain").set(config, "lokal:llama3.1");
        ConfigSchema.byPath("llm.smartChain").set(config, "lokal:llama3.1-70b");
        ConfigSchema.byPath("llm.visionChain").set(config, "lokal:llava");
        EchoProvider lokal = new EchoProvider("lokal");

        GatewayLlmClient client = GatewayLlmClient.withProviders(config, Map.of("lokal", lokal));
        LlmAnswer fast = client.complete(Tier.FAST, "system", "text");
        client.complete(Tier.SMART, null, "text");

        assertThat(fast.text()).isEqualTo("antwort von lokal");
        assertThat(fast.model()).isEqualTo("lokal/llama3.1");
        assertThat(fast.tokensUsed()).isEqualTo(3);
        assertThat(lokal.models).containsExactly("llama3.1", "llama3.1-70b");
    }

    @Test
    @DisplayName("should refuse to start without any provider for the chains")
    void should_refuseWithoutProviders() {
        StoneAiConfig config = StoneAiConfig.defaults();

        assertThatThrownBy(() -> GatewayLlmClient.withProviders(config, Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
