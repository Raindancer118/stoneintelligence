package de.tstieh.stoneintelligence.platform.device;

import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Plugin-Listen als jsonb-Text: {@code [{"id":"calendar","version":"1.5.10"}]}. */
final class PluginRefsJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PluginRefsJson() {
    }

    static String write(List<PluginRef> plugins) {
        try {
            return MAPPER.writeValueAsString(plugins);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<PluginRef> read(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<PluginRef>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
