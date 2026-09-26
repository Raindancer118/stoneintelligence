package de.tstieh.stoneintelligence.platform.ai;

import java.util.List;

/** Ein KI-Dienst, wie ihn Worker und Dashboard sehen - ohne Endpunkte oder Schluessel. */
public record AiServiceResponse(String id, String name, List<Integer> levels) {

    static AiServiceResponse from(AiService service) {
        return new AiServiceResponse(service.id(), service.name(), service.allowedLevels().stream().sorted().toList());
    }
}
