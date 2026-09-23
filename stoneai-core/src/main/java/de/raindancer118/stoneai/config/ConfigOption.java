package de.raindancer118.stoneai.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * One editable setting: its dotted path, what it means, and how to read and write it as text.
 * Everything that edits configuration — the {@code config} subcommand, the interactive UI, the
 * YAML loader — goes through options, so a new setting is added in exactly one place
 * ({@link ConfigSchema}) and is immediately available everywhere.
 */
public record ConfigOption(String path,
                           String section,
                           Kind kind,
                           String description,
                           List<String> choices,
                           Function<StoneAiConfig, String> reader,
                           BiConsumer<StoneAiConfig, String> writer) {

    public enum Kind {
        TEXT, PATH, BOOLEAN, INTEGER, DECIMAL, LIST
    }

    /** The current value as the text a user would type. */
    public String render(StoneAiConfig config) {
        return reader.apply(config);
    }

    /** Parses and applies {@code value}, failing loudly if it does not fit this option. */
    public void set(StoneAiConfig config, String value) {
        writer.accept(config, value);
    }

    public String name() {
        return path.substring(path.indexOf('.') + 1);
    }

    static ConfigOption text(String path, String description,
                             Function<StoneAiConfig, String> reader,
                             BiConsumer<StoneAiConfig, String> writer) {
        return new ConfigOption(path, sectionOf(path), Kind.TEXT, description, List.of(),
                reader, (config, value) -> writer.accept(config, value.trim()));
    }

    static ConfigOption path(String path, String description,
                             Function<StoneAiConfig, String> reader,
                             BiConsumer<StoneAiConfig, String> writer) {
        return new ConfigOption(path, sectionOf(path), Kind.PATH, description, List.of(),
                reader, (config, value) -> {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                throw new ConfigException(path + ": a path must not be empty");
            }
            writer.accept(config, trimmed);
        });
    }

    static ConfigOption flag(String path, String description,
                             Function<StoneAiConfig, Boolean> reader,
                             BiConsumer<StoneAiConfig, Boolean> writer) {
        return new ConfigOption(path, sectionOf(path), Kind.BOOLEAN, description,
                List.of("true", "false"),
                config -> Boolean.toString(reader.apply(config)),
                (config, value) -> {
                    String normalised = value.trim().toLowerCase(java.util.Locale.ROOT);
                    boolean parsed = switch (normalised) {
                        case "true", "yes", "ja", "on", "1" -> true;
                        case "false", "no", "nein", "off", "0" -> false;
                        default -> throw new ConfigException(
                                path + ": expected true or false, got '" + value + "'");
                    };
                    writer.accept(config, parsed);
                });
    }

    static ConfigOption integer(String path, String description, int min, int max,
                                Function<StoneAiConfig, Integer> reader,
                                BiConsumer<StoneAiConfig, Integer> writer) {
        return new ConfigOption(path, sectionOf(path), Kind.INTEGER, description, List.of(),
                config -> Integer.toString(reader.apply(config)),
                (config, value) -> {
                    int parsed;
                    try {
                        parsed = Integer.parseInt(value.trim());
                    } catch (NumberFormatException e) {
                        throw new ConfigException(path + ": expected a whole number, got '" + value + "'", e);
                    }
                    if (parsed < min || parsed > max) {
                        throw new ConfigException(path + ": " + parsed + " is outside " + min + "…" + max);
                    }
                    writer.accept(config, parsed);
                });
    }

    static ConfigOption decimal(String path, String description, double min, double max,
                                Function<StoneAiConfig, Double> reader,
                                BiConsumer<StoneAiConfig, Double> writer) {
        return new ConfigOption(path, sectionOf(path), Kind.DECIMAL, description, List.of(),
                config -> trimTrailingZero(reader.apply(config)),
                (config, value) -> {
                    double parsed;
                    try {
                        parsed = Double.parseDouble(value.trim());
                    } catch (NumberFormatException e) {
                        throw new ConfigException(path + ": expected a decimal number, got '" + value + "'", e);
                    }
                    if (parsed < min || parsed > max) {
                        throw new ConfigException(path + ": " + parsed + " is outside " + min + "…" + max);
                    }
                    writer.accept(config, parsed);
                });
    }

    static ConfigOption list(String path, String description,
                             Function<StoneAiConfig, List<String>> reader,
                             BiConsumer<StoneAiConfig, List<String>> writer) {
        return new ConfigOption(path, sectionOf(path), Kind.LIST, description, List.of(),
                config -> String.join(", ", reader.apply(config)),
                (config, value) -> writer.accept(config, parseList(value)));
    }

    /** Splits a comma separated value, dropping blanks so trailing commas are harmless. */
    public static List<String> parseList(String value) {
        List<String> items = new ArrayList<>();
        for (String part : Arrays.asList(value.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    private static String trimTrailingZero(double value) {
        String rendered = Double.toString(value);
        return rendered.endsWith(".0") ? rendered.substring(0, rendered.length() - 2) : rendered;
    }

    private static String sectionOf(String path) {
        return path.substring(0, path.indexOf('.'));
    }
}
