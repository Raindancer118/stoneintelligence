package de.raindancer118.stoneai.llm;

import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.extract.LlmAnswer;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.extract.Tier;
import io.github.raindancer118.aigateway.AiGateway;
import io.github.raindancer118.aigateway.AiGatewayException;
import io.github.raindancer118.aigateway.AiProvider;
import io.github.raindancer118.aigateway.ChatMessage;
import io.github.raindancer118.aigateway.ChatRequest;
import io.github.raindancer118.aigateway.ChatResponse;
import io.github.raindancer118.aigateway.ModelTier;
import io.github.raindancer118.aigateway.Route;
import io.github.raindancer118.aigateway.provider.GoogleGeminiProvider;
import io.github.raindancer118.aigateway.provider.OpenAiCompatibleProvider;
import io.github.raindancer118.revolver.ChamberStats;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Routes StoneAI's model calls through the shared AI gateway, which already owns the parts that
 * are tedious to get right: per-provider key pools with rotation, rate-limit detection and a
 * fallback chain that skips a provider that is out of capacity instead of failing the run.
 *
 * <p>Keys come from the environment ({@code GROQ_API_KEY} or {@code GROQ_API_KEY_1…n}, same for
 * {@code GOOGLE_API_KEY}, {@code MISTRAL_API_KEY}, {@code OPENROUTER_API_KEY}) and never from the
 * config file, so a config can be synced or shared without leaking credentials.
 */
public final class GatewayLlmClient implements LlmClient {

    private static final Map<String, String> ENV_PREFIXES = Map.of(
            "groq", "GROQ",
            "gemini", "GOOGLE",
            "google", "GOOGLE",
            "mistral", "MISTRAL",
            "openrouter", "OPENROUTER");

    private final AiGateway gateway;
    private final StoneAiConfig config;
    private final Map<String, AiProvider> providers;

    private GatewayLlmClient(AiGateway gateway, StoneAiConfig config, Map<String, AiProvider> providers) {
        this.gateway = gateway;
        this.config = config;
        this.providers = providers;
    }

    /** Providers and keys from the environment - the CLI's way. */
    public static GatewayLlmClient create(StoneAiConfig config) throws IOException {
        Map<String, AiProvider> providers = buildProviders(config);
        if (providers.isEmpty()) {
            throw new IllegalStateException("""
                    Keine API-Keys gefunden. StoneAI liest sie aus der Umgebung, nicht aus der Config:
                      GROQ_API_KEY   (oder GROQ_API_KEY_1, GROQ_API_KEY_2, …)
                      GOOGLE_API_KEY (oder GOOGLE_API_KEY_1, …)
                    Optional zusätzlich: MISTRAL_API_KEY, OPENROUTER_API_KEY.""");
        }
        return withProviders(config, providers);
    }

    /**
     * A client over providers the caller built - for hosts that manage keys and endpoints
     * themselves (StoneIntelligence: one set per configured AI service, including any
     * OpenAI-compatible endpoint). The config's model chains name providers by these keys.
     */
    public static GatewayLlmClient withProviders(StoneAiConfig config, Map<String, AiProvider> providers) {
        Map<String, AiProvider> usable = new LinkedHashMap<>(providers);
        List<Route> fast = routes(config.llm().fastChain(), usable);
        if (fast.isEmpty() && routes(config.llm().smartChain(), usable).isEmpty()) {
            throw new IllegalStateException("Kein Provider der Modell-Ketten ist verfügbar: " + usable.keySet());
        }
        AiGateway.Builder builder = AiGateway.builder();
        builder.route(ModelTier.FAST, routes(config.llm().fastChain(), providers));
        builder.route(ModelTier.SMART, routes(config.llm().smartChain(), providers));
        builder.route(ModelTier.BALANCED, routes(config.llm().visionChain(), providers));
        return new GatewayLlmClient(builder.build(), config, usable);
    }

    /** The providers that could be built, for {@code stoneai status}. */
    public List<String> availableProviders() {
        return List.copyOf(providers.keySet());
    }

    /**
     * One line per provider describing its key pool — how many keys, how many usable, how they
     * have been doing. Deliberately <em>not</em> the raw {@code ChamberStats}: those carry the API
     * keys themselves, and a status command's output ends up in terminals, scrollback, pasted bug
     * reports and CI logs.
     */
    public Map<String, String> keyPoolStatus() {
        Map<String, String> summary = new LinkedHashMap<>();
        gateway.status().forEach((provider, chambers) -> {
            long available = chambers.stream().filter(ChamberStats::available).count();
            long usages = chambers.stream().mapToLong(ChamberStats::usages).sum();
            long errors = chambers.stream().mapToLong(ChamberStats::errors).sum();
            long rateLimited = chambers.stream().mapToLong(ChamberStats::rateLimitHits).sum();
            summary.put(provider, "%d Key(s), %d verfügbar · %d Aufrufe, %d Fehler, %d Rate-Limits"
                    .formatted(chambers.size(), available, usages, errors, rateLimited));
        });
        return summary;
    }

    @Override
    public LlmAnswer complete(Tier tier, String system, String user) {
        ChatRequest.Builder request = ChatRequest.builder().temperature(config.llm().temperature())
                .maxTokens(config.llm().maxOutputTokens());
        if (system != null && !system.isBlank()) {
            request.system(system);
        }
        request.user(user);
        return answer(gatewayTier(tier), request.build());
    }

    @Override
    public LlmAnswer readImage(byte[] pngImage, String prompt) {
        ChatRequest request = ChatRequest.builder()
                .message(ChatMessage.userWithImage(prompt, "image/png", pngImage))
                .temperature(0.0)
                .maxTokens(config.llm().maxOutputTokens())
                .build();
        return answer(ModelTier.BALANCED, request);
    }

    private LlmAnswer answer(ModelTier tier, ChatRequest request) {
        try {
            ChatResponse response = gateway.chat(tier, request);
            return new LlmAnswer(response.content(), response.usage().totalTokens(),
                    response.provider() + "/" + response.model());
        } catch (AiGatewayException e) {
            throw new IllegalStateException("kein Provider konnte antworten: " + e.getMessage(), e);
        }
    }

    private static ModelTier gatewayTier(Tier tier) {
        return switch (tier) {
            case FAST -> ModelTier.FAST;
            case SMART -> ModelTier.SMART;
            case VISION -> ModelTier.BALANCED;
        };
    }

    /** Turns {@code provider:model} entries into gateway routes, skipping providers without keys. */
    private static List<Route> routes(List<String> chain, Map<String, AiProvider> providers) {
        List<Route> routes = new ArrayList<>();
        for (String entry : chain) {
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                throw new IllegalStateException(
                        "Ungültiger Eintrag in der Modell-Kette: '" + entry + "' (erwartet provider:model)");
            }
            String provider = entry.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String model = entry.substring(colon + 1).trim();
            AiProvider resolved = providers.get(provider);
            if (resolved != null) {
                routes.add(new Route(resolved, model));
            }
        }
        return routes;
    }

    private static Map<String, AiProvider> buildProviders(StoneAiConfig config) throws IOException {
        Map<String, AiProvider> providers = new LinkedHashMap<>();
        for (String name : neededProviders(config)) {
            String prefix = ENV_PREFIXES.get(name);
            if (prefix == null) {
                continue;
            }
            List<String> keys = keysFor(prefix);
            if (keys.isEmpty()) {
                continue;
            }
            providers.put(name, switch (name) {
                case "groq" -> OpenAiCompatibleProvider.groq(keys);
                case "mistral" -> OpenAiCompatibleProvider.mistral(keys);
                case "openrouter" -> OpenAiCompatibleProvider.openRouter(keys);
                default -> GoogleGeminiProvider.create(keys);
            });
        }
        return providers;
    }

    private static List<String> neededProviders(StoneAiConfig config) {
        List<String> names = new ArrayList<>();
        for (List<String> chain : List.of(config.llm().fastChain(), config.llm().smartChain(),
                config.llm().visionChain())) {
            for (String entry : chain) {
                int colon = entry.indexOf(':');
                String provider = (colon > 0 ? entry.substring(0, colon) : entry)
                        .trim().toLowerCase(Locale.ROOT);
                if (!names.contains(provider)) {
                    names.add(provider);
                }
            }
        }
        return names;
    }

    /**
     * {@code PREFIX_API_KEY} plus every numbered {@code PREFIX_API_KEY_n}, and the plural
     * {@code PREFIX_API_KEYS}. Each variable may itself hold a comma separated pool, which is how
     * key pools are usually stored — a single variable with four keys is far more common in the
     * wild than four variables, and treating it as one long key fails with an unhelpful 401.
     */
    public static List<String> keysFor(String prefix) {
        List<String> keys = new ArrayList<>();
        addKeys(keys, System.getenv(prefix + "_API_KEY"));
        addKeys(keys, System.getenv(prefix + "_API_KEYS"));
        for (int number = 1; number <= 16; number++) {
            addKeys(keys, System.getenv(prefix + "_API_KEY_" + number));
        }
        return keys;
    }

    /** Splits one variable's value into individual keys — visible for testing. */
    public static List<String> splitKeys(String value) {
        List<String> keys = new ArrayList<>();
        addKeys(keys, value);
        return keys;
    }

    private static void addKeys(List<String> keys, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String candidate : value.split("[,;\\s]+")) {
            String key = candidate.trim();
            if (!key.isEmpty() && !keys.contains(key)) {
                keys.add(key);
            }
        }
    }
}
