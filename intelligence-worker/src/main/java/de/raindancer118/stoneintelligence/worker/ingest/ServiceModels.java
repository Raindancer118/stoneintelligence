package de.raindancer118.stoneintelligence.worker.ingest;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import de.raindancer118.stoneai.config.StoneAiConfig;

/**
 * Welche Modelle ein KI-Dienst nutzt - im Worker, nicht in platform-api, weil nur hier Endpunkte
 * und Schluessel gebraucht werden. Je Dienst (Id aus {@code STONEINTELLIGENCE_AI_SERVICES},
 * Grossbuchstaben, {@code -} → {@code _}) optional:
 * <ul>
 *   <li>{@code AI_SERVICE_<ID>_FAST|_SMART|_VISION} - Modellketten {@code provider:model, …}
 *       (Standard: die StoneAI-Ketten ueber ai-gateway);</li>
 *   <li>{@code AI_SERVICE_<ID>_ENDPOINT} - beliebiger OpenAI-kompatibler Endpunkt (Ollama, vLLM
 *       …), in den Ketten unter der Dienst-Id ansprechbar, dazu {@code _KEYS} (leer = ohne).</li>
 * </ul>
 * Schluessel der eingebauten Anbieter wie gehabt aus {@code GROQ_API_KEY}, {@code GOOGLE_API_KEY} …
 */
final class ServiceModels {

    record ServiceModel(String serviceId, List<String> fastChain, List<String> smartChain, List<String> visionChain,
                        String endpoint, List<String> keys) { }

    private final Map<String, String> env;

    private ServiceModels(Map<String, String> env) {
        this.env = env;
    }

    static ServiceModels from(Map<String, String> env) {
        return new ServiceModels(Map.copyOf(env));
    }

    ServiceModel forService(String serviceId) {
        var prefix = "AI_SERVICE_" + serviceId.toUpperCase(Locale.ROOT).replace('-', '_') + "_";
        var defaults = StoneAiConfig.defaults().llm();
        var endpoint = env.get(prefix + "ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            var uri = URI.create(endpoint.strip());
            if (uri.getScheme() == null || !uri.getScheme().matches("(?i)https?") || uri.getHost() == null) {
                throw new IllegalArgumentException(prefix + "ENDPOINT muss eine http(s)-URL sein");
            }
        }
        return new ServiceModel(serviceId,
            chain(prefix + "FAST", defaults.fastChain()),
            chain(prefix + "SMART", defaults.smartChain()),
            chain(prefix + "VISION", defaults.visionChain()),
            endpoint == null || endpoint.isBlank() ? null : endpoint.strip(),
            list(env.getOrDefault(prefix + "KEYS", "")));
    }

    private List<String> chain(String variable, List<String> fallback) {
        var configured = list(env.getOrDefault(variable, ""));
        return configured.isEmpty() ? List.copyOf(fallback) : configured;
    }

    private static List<String> list(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(entry -> !entry.isEmpty()).toList();
    }
}
