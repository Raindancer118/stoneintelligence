package de.raindancer118.stoneai.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the config file. Loading merges the file onto the defaults, so a partial file
 * stays valid across upgrades; unknown keys are an error rather than a silent no-op, because a
 * typo that is quietly ignored looks exactly like a setting that does not work.
 */
public final class ConfigStore {

    private ConfigStore() {
    }

    /** {@code ~/.config/stoneai/config.yaml}, honouring {@code XDG_CONFIG_HOME}. */
    public static Path defaultLocation() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg == null || xdg.isBlank())
                ? Path.of(System.getProperty("user.home"), ".config")
                : Path.of(xdg);
        return base.resolve("stoneai").resolve("config.yaml");
    }

    public static StoneAiConfig load(Path file) throws IOException {
        StoneAiConfig config = StoneAiConfig.defaults();
        if (!Files.exists(file)) {
            return config;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, Object> tree = parse(text, file);
        for (Map.Entry<String, String> entry : flatten(tree).entrySet()) {
            String path = entry.getKey();
            if (!ConfigSchema.has(path)) {
                throw new ConfigException(file + ": unknown setting '" + path + "'");
            }
            ConfigSchema.byPath(path).set(config, entry.getValue());
        }
        return config;
    }

    public static void save(Path file, StoneAiConfig config) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".stoneai-config", ".tmp");
        try {
            Files.writeString(temp, render(config), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** The config as it is written to disk: sections in schema order, every option documented. */
    public static String render(StoneAiConfig config) {
        StringBuilder out = new StringBuilder();
        out.append("# StoneAI — Konfiguration\n")
                .append("# Jede Option lässt sich auch über die CLI ändern:\n")
                .append("#   stoneai config set <pfad> <wert>      einzelne Option setzen\n")
                .append("#   stoneai config                        interaktiv, mit Pfeiltasten\n")
                .append("# API-Keys gehören NICHT hierher — sie kommen aus GROQ_API_KEY / GOOGLE_API_KEY.\n");
        for (String section : ConfigSchema.sections()) {
            out.append('\n').append(section).append(":\n");
            for (ConfigOption option : ConfigSchema.sectionOptions(section)) {
                out.append("  # ").append(option.description()).append('\n');
                out.append("  ").append(option.name()).append(": ")
                        .append(renderValue(option, config)).append('\n');
            }
        }
        return out.toString();
    }

    private static String renderValue(ConfigOption option, StoneAiConfig config) {
        String value = option.render(config);
        if (option.kind() == ConfigOption.Kind.LIST) {
            List<String> items = ConfigOption.parseList(value);
            List<String> quoted = new ArrayList<>();
            for (String item : items) {
                quoted.add(quote(item));
            }
            return "[" + String.join(", ", quoted) + "]";
        }
        if (option.kind() == ConfigOption.Kind.BOOLEAN
                || option.kind() == ConfigOption.Kind.INTEGER
                || option.kind() == ConfigOption.Kind.DECIMAL) {
            return value;
        }
        return quote(value);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text, Path file) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(text);
        } catch (YAMLException e) {
            throw new ConfigException(file + ": not valid YAML — " + e.getMessage(), e);
        }
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new ConfigException(file + ": expected a mapping of sections at the top level");
        }
        return (Map<String, Object>) map;
    }

    /** Turns the nested YAML tree into {@code section.option -> text} pairs. */
    private static Map<String, String> flatten(Map<String, Object> tree) {
        Map<String, String> flat = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> section : tree.entrySet()) {
            Object value = section.getValue();
            if (!(value instanceof Map<?, ?> entries)) {
                throw new ConfigException("section '" + section.getKey() + "' must contain settings");
            }
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                flat.put(section.getKey() + "." + entry.getKey(), asText(entry.getValue()));
            }
        }
        return flat;
    }

    private static String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            for (Object item : list) {
                items.add(String.valueOf(item));
            }
            return String.join(", ", items);
        }
        return String.valueOf(value);
    }
}
