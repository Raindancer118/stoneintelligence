package de.tstieh.stoneintelligence.platform.device;

import java.util.regex.Pattern;

/** Ein Community-Plugin: Kennung wie in dessen manifest.json, Version optional (bei der Richtlinie: {@code null} = jede). */
public record PluginRef(String id, String version) {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._+-]{1,50}");

    public PluginRef {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid plugin id");
        }
        if (version != null && !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("invalid plugin version");
        }
    }

    public String label() {
        return version == null ? id : id + " " + version;
    }
}
