package de.tstieh.stoneintelligence.platform.ai;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Die konfigurierten KI-Dienste - Ids und Namen sind eindeutig. */
public final class AiServiceDirectory {

    private final Map<String, AiService> byId;

    public AiServiceDirectory(Collection<AiService> services) {
        byId = services.stream().collect(Collectors.toUnmodifiableMap(AiService::id, Function.identity(), (a, b) -> {
            throw new IllegalArgumentException("AI service id configured twice: " + a.id());
        }));
        if (services.stream().map(AiService::name).distinct().count() != services.size()) {
            throw new IllegalArgumentException("AI service names must be unique - they are the agent identity");
        }
    }

    /**
     * Liest {@code STONEINTELLIGENCE_AI_SERVICES}: {@code id|Name|Levels} je Dienst, durch {@code ;}
     * getrennt, Levels durch {@code ,} - z. B. {@code gemini|Gemini|1; lokal|Ollama lokal|1,2}. Leer =
     * keine KI. Eine fehlerhafte Angabe verhindert den Start, statt still einen Dienst wegzulassen.
     */
    public static AiServiceDirectory parse(String setting) {
        if (setting == null || setting.isBlank()) {
            return new AiServiceDirectory(List.of());
        }
        var services = new java.util.ArrayList<AiService>();
        for (var entry : setting.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            var parts = entry.strip().split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("AI service must be 'id|Name|levels', was: " + entry.strip());
            }
            var levels = new java.util.HashSet<Integer>();
            for (var level : parts[2].split(",")) {
                try {
                    levels.add(Integer.parseInt(level.strip()));
                } catch (NumberFormatException notANumber) {
                    throw new IllegalArgumentException("AI service " + parts[0].strip() + " has an invalid level: " + level.strip());
                }
            }
            services.add(new AiService(parts[0].strip(), parts[1].strip(), levels));
        }
        return new AiServiceDirectory(services);
    }

    public Optional<AiService> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<AiService> all() {
        return byId.values().stream().sorted(java.util.Comparator.comparing(AiService::name)).toList();
    }
}
