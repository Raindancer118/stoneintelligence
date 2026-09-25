package de.raindancer118.stoneintelligence.worker.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import de.raindancer118.stoneai.config.ConfigSchema;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.llm.GatewayLlmClient;
import io.github.raindancer118.aigateway.AiProvider;
import io.github.raindancer118.aigateway.ProviderCapacity;
import io.github.raindancer118.aigateway.provider.GoogleGeminiProvider;
import io.github.raindancer118.aigateway.provider.OpenAiCompatibleProvider;

/**
 * Baut je KI-Dienst einen Client ueber ai-gateway (Standard): die eingebauten Anbieter mit den
 * Schluesseln aus der Umgebung, dazu optional den eigenen OpenAI-kompatiblen Endpunkt des Dienstes.
 * Einmal gebaut, wird der Client wiederverwendet - die Schluessel-Pools behalten so ihre
 * Rate-Limit-Erfahrung.
 */
public final class GatewayLlmFactory implements LlmFactory {

    private final Map<String, GatewayLlmClient> clients = new ConcurrentHashMap<>();

    @Override
    public LlmClient forService(ServiceModels.ServiceModel model) {
        return client(model);
    }

    @Override
    public Map<String, ProviderCapacity> capacity(ServiceModels.ServiceModel model) {
        return client(model).capacity();
    }

    @Override
    public Map<String, ProviderCapacity> refreshCapacity(ServiceModels.ServiceModel model) {
        return client(model).refreshCapacity();
    }

    private GatewayLlmClient client(ServiceModels.ServiceModel model) {
        return clients.computeIfAbsent(model.serviceId(), id -> build(model));
    }

    static StoneAiConfig configFor(ServiceModels.ServiceModel model) {
        var config = StoneAiConfig.defaults();
        ConfigSchema.byPath("llm.fastChain").set(config, String.join(",", model.fastChain()));
        ConfigSchema.byPath("llm.smartChain").set(config, String.join(",", model.smartChain()));
        ConfigSchema.byPath("llm.visionChain").set(config, String.join(",", model.visionChain()));
        return config;
    }

    private static GatewayLlmClient build(ServiceModels.ServiceModel model) {
        try {
            Map<String, AiProvider> providers = new LinkedHashMap<>();
            addKeyed(providers, "groq", "GROQ");
            addKeyed(providers, "gemini", "GOOGLE");
            addKeyed(providers, "google", "GOOGLE");
            addKeyed(providers, "mistral", "MISTRAL");
            addKeyed(providers, "openrouter", "OPENROUTER");
            if (model.endpoint() != null) {
                providers.put(model.serviceId(), OpenAiCompatibleProvider.custom(model.serviceId(), model.endpoint(), model.keys()));
            }
            return GatewayLlmClient.withProviders(configFor(model), providers);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void addKeyed(Map<String, AiProvider> providers, String name, String envPrefix) throws IOException {
        List<String> keys = GatewayLlmClient.keysFor(envPrefix);
        if (keys.isEmpty()) {
            return;
        }
        providers.put(name, switch (name) {
            case "groq" -> OpenAiCompatibleProvider.groq(keys);
            case "mistral" -> OpenAiCompatibleProvider.mistral(keys);
            case "openrouter" -> OpenAiCompatibleProvider.openRouter(keys);
            default -> GoogleGeminiProvider.create(keys);
        });
    }
}
