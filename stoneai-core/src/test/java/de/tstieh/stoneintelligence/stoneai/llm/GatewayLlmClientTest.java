package de.tstieh.stoneintelligence.stoneai.llm;

import de.tstieh.stoneintelligence.stoneai.config.ConfigSchema;
import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import de.tstieh.stoneintelligence.stoneai.extract.LlmAnswer;
import de.tstieh.stoneintelligence.stoneai.extract.Tier;
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
        final List<Integer> maxTokens = new ArrayList<>();

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
            maxTokens.add(request.maxTokens());
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

    // Rich notes are long, and reasoning models spend part of the budget thinking: a provider's
    // default limit cut answers off mid-JSON.
    @Test
    @DisplayName("should ask for a generous, configurable answer length")
    void should_setTheOutputLimit() {
        StoneAiConfig config = StoneAiConfig.defaults();
        ConfigSchema.byPath("llm.fastChain").set(config, "lokal:m");
        ConfigSchema.byPath("llm.smartChain").set(config, "lokal:m");
        ConfigSchema.byPath("llm.visionChain").set(config, "lokal:m");
        EchoProvider lokal = new EchoProvider("lokal");
        GatewayLlmClient client = GatewayLlmClient.withProviders(config, Map.of("lokal", lokal));

        client.complete(Tier.FAST, "s", "t");
        ConfigSchema.byPath("llm.maxOutputTokens").set(config, "4096");
        client.complete(Tier.FAST, "s", "t");

        assertThat(lokal.maxTokens).containsExactly(16_384, 4_096);
    }

    /** Fails with the given exceptions first, then answers. */
    private static final class FlakyProvider implements AiProvider {
        private final java.util.Deque<io.github.raindancer118.aigateway.AiProviderException> failures;
        int calls;

        FlakyProvider(io.github.raindancer118.aigateway.AiProviderException... failures) {
            this.failures = new java.util.ArrayDeque<>(List.of(failures));
        }

        @Override
        public String name() {
            return "gemini";
        }

        @Override
        public ChatResponse chat(String model, ChatRequest request) throws io.github.raindancer118.aigateway.AiProviderException {
            calls++;
            if (!failures.isEmpty()) {
                throw failures.pollFirst();
            }
            return new ChatResponse("antwort", "gemini", model, new Usage(1, 2, 3));
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

    private static StoneAiConfig oneRoute() {
        StoneAiConfig config = StoneAiConfig.defaults();
        ConfigSchema.byPath("llm.fastChain").set(config, "gemini:m");
        ConfigSchema.byPath("llm.smartChain").set(config, "gemini:m");
        ConfigSchema.byPath("llm.visionChain").set(config, "gemini:m");
        return config;
    }

    // Gemini answered 503 "high demand" for minutes at a time - one failed call used to end a
    // 200-slide run. Overload passes; waiting is cheaper than starting over.
    @Test
    @DisplayName("should wait and try again while every provider is overloaded")
    void should_retryWithBackoff_whenProvidersAreOverloaded() {
        FlakyProvider gemini = new FlakyProvider(new io.github.raindancer118.aigateway.RetryableAiException("HTTP 503"),
                new io.github.raindancer118.aigateway.RetryableAiException("HTTP 503"));
        List<java.time.Duration> waits = new ArrayList<>();
        GatewayLlmClient client = GatewayLlmClient.withProviders(oneRoute(), Map.of("gemini", gemini)).sleepingWith(waits::add);

        assertThat(client.complete(Tier.FAST, "s", "t").text()).isEqualTo("antwort");
        assertThat(waits).containsExactly(java.time.Duration.ofSeconds(20), java.time.Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("should give up after the configured attempts")
    void should_giveUp_afterTheLastAttempt() {
        StoneAiConfig config = oneRoute();
        ConfigSchema.byPath("llm.retryAttempts").set(config, "2");
        FlakyProvider gemini = new FlakyProvider(new io.github.raindancer118.aigateway.RetryableAiException("HTTP 503"),
                new io.github.raindancer118.aigateway.RetryableAiException("HTTP 503"),
                new io.github.raindancer118.aigateway.RetryableAiException("HTTP 503"));
        List<java.time.Duration> waits = new ArrayList<>();
        GatewayLlmClient client = GatewayLlmClient.withProviders(config, Map.of("gemini", gemini)).sleepingWith(waits::add);

        assertThatThrownBy(() -> client.complete(Tier.FAST, "s", "t")).isInstanceOf(IllegalStateException.class);
        assertThat(gemini.calls).isEqualTo(2);
        assertThat(waits).hasSize(1);
    }

    // A request that is too large or a model that does not exist stays so - waiting changes nothing.
    @Test
    @DisplayName("should not wait when no provider failed for a passing reason")
    void should_notRetry_whenTheFailureIsPermanent() {
        FlakyProvider gemini = new FlakyProvider(new io.github.raindancer118.aigateway.AiProviderException("HTTP 413 Request too large"));
        List<java.time.Duration> waits = new ArrayList<>();
        GatewayLlmClient client = GatewayLlmClient.withProviders(oneRoute(), Map.of("gemini", gemini)).sleepingWith(waits::add);

        assertThatThrownBy(() -> client.complete(Tier.FAST, "s", "t")).isInstanceOf(IllegalStateException.class);
        assertThat(waits).isEmpty();
    }

    // An empty daily quota does not pass in a minute - sleeping through it would hold the job
    // (and the worker) for hours. The caller gets told when to come back instead.
    @Test
    @DisplayName("should hand back the moment capacity returns instead of waiting hours for it")
    void should_throwCapacity_when_exhaustedForLong() {
        java.time.Instant back = java.time.Instant.now().plus(java.time.Duration.ofHours(5));
        FlakyProvider gemini = new FlakyProvider(new io.github.raindancer118.aigateway.CapacityExhaustedException("gemini: 429", back));
        List<java.time.Duration> waits = new ArrayList<>();
        GatewayLlmClient client = GatewayLlmClient.withProviders(oneRoute(), Map.of("gemini", gemini)).sleepingWith(waits::add);

        assertThatThrownBy(() -> client.complete(Tier.FAST, "s", "t"))
                .isInstanceOfSatisfying(de.tstieh.stoneintelligence.stoneai.extract.LlmCapacityException.class,
                        e -> assertThat(e.availableAgainAt()).isEqualTo(back));
        assertThat(waits).isEmpty();
        assertThat(gemini.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("should wait out a short rate limit and carry on")
    void should_waitOutShortRateLimit() {
        java.time.Instant back = java.time.Instant.now().plusSeconds(30);
        FlakyProvider gemini = new FlakyProvider(new io.github.raindancer118.aigateway.CapacityExhaustedException("gemini: 429", back));
        List<java.time.Duration> waits = new ArrayList<>();
        GatewayLlmClient client = GatewayLlmClient.withProviders(oneRoute(), Map.of("gemini", gemini)).sleepingWith(waits::add);

        assertThat(client.complete(Tier.FAST, "s", "t").text()).isEqualTo("antwort");
        assertThat(waits).hasSize(1);
        assertThat(waits.get(0)).isBetween(java.time.Duration.ofSeconds(25), java.time.Duration.ofSeconds(32));
    }

    @Test
    @DisplayName("should report each provider's capacity without its keys")
    void should_reportCapacity() {
        FlakyProvider gemini = new FlakyProvider();
        GatewayLlmClient client = GatewayLlmClient.withProviders(oneRoute(), Map.of("gemini", gemini));

        assertThat(client.capacity()).containsOnlyKeys("gemini");
        assertThat(client.refreshCapacity().get("gemini").exhausted()).isFalse();
    }
}
