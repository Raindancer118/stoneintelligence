package de.tstieh.stoneintelligence.platform.device;

import java.util.List;

/** Was das Plugin beim Verbinden und danach regelmaessig meldet; {@code wiped}: der Loeschauftrag ist ausgefuehrt. */
public record DeviceReport(String name, String platform, String obsidianVersion, String pluginVersion,
                           boolean restrictedMode, List<PluginRef> plugins, boolean wiped) {

    static final int MAX_PLUGINS = 1000;
    static final int MAX_TEXT = 200;

    public DeviceReport {
        name = text(name, "name");
        platform = text(platform, "platform");
        obsidianVersion = text(obsidianVersion, "obsidianVersion");
        pluginVersion = text(pluginVersion, "pluginVersion");
        if (plugins == null || plugins.size() > MAX_PLUGINS || plugins.stream().anyMatch(p -> p == null)) {
            throw new IllegalArgumentException("invalid plugin list");
        }
        plugins = List.copyOf(plugins);
    }

    private static String text(String value, String field) {
        var stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_TEXT || stripped.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return stripped;
    }
}
