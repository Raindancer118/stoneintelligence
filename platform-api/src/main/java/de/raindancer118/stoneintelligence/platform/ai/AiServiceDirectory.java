package de.raindancer118.stoneintelligence.platform.ai;

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

    public Optional<AiService> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<AiService> all() {
        return byId.values().stream().sorted(java.util.Comparator.comparing(AiService::name)).toList();
    }
}
