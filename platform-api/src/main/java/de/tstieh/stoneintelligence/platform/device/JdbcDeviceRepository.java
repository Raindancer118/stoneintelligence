package de.tstieh.stoneintelligence.platform.device;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeviceRepository implements DeviceRepository {

    private static final String COLUMNS = """
        vault_id, device_id, subject, name, platform, obsidian_version, plugin_version, restricted_mode,
        plugins::text AS plugins, first_seen, last_seen, revoked_at, revoked_by, wipe_requested_at, wiped_at
        """;

    private final JdbcClient jdbcClient;

    public JdbcDeviceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<Device> find(VaultId vaultId, String deviceId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM platform.devices WHERE vault_id = :vaultId AND device_id = :deviceId")
            .param("vaultId", vaultId.value())
            .param("deviceId", deviceId)
            .query(JdbcDeviceRepository::map)
            .optional();
    }

    @Override
    public void save(Device device) {
        jdbcClient.sql("""
                INSERT INTO platform.devices (vault_id, device_id, subject, name, platform, obsidian_version, plugin_version,
                    restricted_mode, plugins, first_seen, last_seen, revoked_at, revoked_by, wipe_requested_at, wiped_at)
                VALUES (:vaultId, :deviceId, :subject, :name, :platform, :obsidianVersion, :pluginVersion,
                    :restrictedMode, CAST(:plugins AS jsonb), :firstSeen, :lastSeen, :revokedAt, :revokedBy, :wipeRequestedAt, :wipedAt)
                ON CONFLICT (vault_id, device_id) DO UPDATE SET
                    subject = EXCLUDED.subject, name = EXCLUDED.name, platform = EXCLUDED.platform,
                    obsidian_version = EXCLUDED.obsidian_version, plugin_version = EXCLUDED.plugin_version,
                    restricted_mode = EXCLUDED.restricted_mode, plugins = EXCLUDED.plugins, last_seen = EXCLUDED.last_seen,
                    revoked_at = EXCLUDED.revoked_at, revoked_by = EXCLUDED.revoked_by,
                    wipe_requested_at = EXCLUDED.wipe_requested_at, wiped_at = EXCLUDED.wiped_at
                """)
            .param("vaultId", device.vaultId().value())
            .param("deviceId", device.deviceId())
            .param("subject", device.subject())
            .param("name", device.name())
            .param("platform", device.platform())
            .param("obsidianVersion", device.obsidianVersion())
            .param("pluginVersion", device.pluginVersion())
            .param("restrictedMode", device.restrictedMode())
            .param("plugins", PluginRefsJson.write(device.plugins()))
            .param("firstSeen", Timestamp.from(device.firstSeen()))
            .param("lastSeen", Timestamp.from(device.lastSeen()))
            .param("revokedAt", timestamp(device.revokedAt()))
            .param("revokedBy", device.revokedBy())
            .param("wipeRequestedAt", timestamp(device.wipeRequestedAt()))
            .param("wipedAt", timestamp(device.wipedAt()))
            .update();
    }

    @Override
    public List<Device> list(VaultId vaultId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM platform.devices WHERE vault_id = :vaultId ORDER BY last_seen DESC")
            .param("vaultId", vaultId.value())
            .query(JdbcDeviceRepository::map)
            .list();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Device map(ResultSet rs, int rowNum) throws SQLException {
        return new Device(VaultId.of(rs.getString("vault_id")), rs.getString("device_id"), rs.getString("subject"),
            rs.getString("name"), rs.getString("platform"), rs.getString("obsidian_version"), rs.getString("plugin_version"),
            rs.getBoolean("restricted_mode"), PluginRefsJson.read(rs.getString("plugins")),
            instant(rs, "first_seen"), instant(rs, "last_seen"), instant(rs, "revoked_at"), rs.getString("revoked_by"),
            instant(rs, "wipe_requested_at"), instant(rs, "wiped_at"));
    }
}
