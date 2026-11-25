package de.tstieh.stoneintelligence.platform.device;

import java.time.Instant;
import java.util.List;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public record Device(VaultId vaultId, String deviceId, String subject, String name, String platform,
                     String obsidianVersion, String pluginVersion, boolean restrictedMode, List<PluginRef> plugins,
                     Instant firstSeen, Instant lastSeen, Instant revokedAt, String revokedBy,
                     Instant wipeRequestedAt, Instant wipedAt) {

    public static Device first(VaultId vaultId, String deviceId, String subject, DeviceReport report, Instant at) {
        return new Device(vaultId, deviceId, subject, report.name(), report.platform(), report.obsidianVersion(),
            report.pluginVersion(), report.restrictedMode(), report.plugins(), at, at, null, null, null, null);
    }

    public boolean revoked() {
        return revokedAt != null;
    }

    /** Ein Loeschauftrag, den das Geraet noch nicht bestaetigt hat. */
    public boolean wipePending() {
        return wipeRequestedAt != null && wipedAt == null;
    }

    public Device reported(String subject, DeviceReport report, Instant at) {
        return new Device(vaultId, deviceId, subject, report.name(), report.platform(), report.obsidianVersion(),
            report.pluginVersion(), report.restrictedMode(), report.plugins(), firstSeen, at,
            revokedAt, revokedBy, wipeRequestedAt, report.wiped() && wipePending() ? at : wipedAt);
    }

    public Device revoke(String by, Instant at) {
        return new Device(vaultId, deviceId, subject, name, platform, obsidianVersion, pluginVersion, restrictedMode,
            plugins, firstSeen, lastSeen, revokedAt != null ? revokedAt : at, revokedAt != null ? revokedBy : by,
            wipeRequestedAt, wipedAt);
    }

    /** Loeschen sperrt mit: ein Geraet, dessen Daten weg sind, soll sich nicht neu verbinden. */
    public Device wipe(String by, Instant at) {
        var revoked = revoke(by, at);
        return new Device(vaultId, deviceId, subject, name, platform, obsidianVersion, pluginVersion, restrictedMode,
            plugins, firstSeen, lastSeen, revoked.revokedAt, revoked.revokedBy, at, null);
    }

    public Device reinstate() {
        return new Device(vaultId, deviceId, subject, name, platform, obsidianVersion, pluginVersion, restrictedMode,
            plugins, firstSeen, lastSeen, null, null, null, null);
    }
}
