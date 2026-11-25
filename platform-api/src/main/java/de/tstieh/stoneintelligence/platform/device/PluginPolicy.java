package de.tstieh.stoneintelligence.platform.device;

import java.util.ArrayList;
import java.util.List;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/**
 * Die Plugin-Richtlinie eines Vaults. Das StoneIntelligence-Plugin selbst ist immer erlaubt: ohne es
 * liesse sich weder melden noch synchronisieren.
 */
public record PluginPolicy(VaultId vaultId, PolicyMode mode, List<PluginRef> allowed) {

    public static final String OWN_PLUGIN_ID = "stoneintelligence";
    public static final int MAX_ALLOWED = 500;

    public PluginPolicy {
        if (allowed.size() > MAX_ALLOWED) {
            throw new IllegalArgumentException("too many plugins");
        }
        if (allowed.stream().map(PluginRef::id).distinct().count() != allowed.size()) {
            throw new IllegalArgumentException("duplicate plugin id");
        }
        allowed = List.copyOf(allowed);
    }

    public static PluginPolicy off(VaultId vaultId) {
        return new PluginPolicy(vaultId, PolicyMode.OFF, List.of());
    }

    public boolean enforcing() {
        return mode == PolicyMode.ENFORCE;
    }

    /** Ob das aktivierte Plugin der Richtlinie genuegt (Kennung erlaubt, und Version gleich, falls festgelegt). */
    public boolean permits(PluginRef plugin) {
        if (OWN_PLUGIN_ID.equals(plugin.id())) {
            return true;
        }
        return allowed.stream().anyMatch(rule -> rule.id().equals(plugin.id())
            && (rule.version() == null || rule.version().equals(plugin.version())));
    }

    /** Alle aktivierten Plugins, die die Richtlinie nicht erlaubt (leer, solange sie nicht durchgesetzt wird). */
    public List<PluginRef> violations(List<PluginRef> enabled) {
        if (!enforcing()) {
            return List.of();
        }
        var offending = new ArrayList<PluginRef>();
        for (var plugin : enabled) {
            if (!permits(plugin)) {
                offending.add(plugin);
            }
        }
        return offending;
    }
}
