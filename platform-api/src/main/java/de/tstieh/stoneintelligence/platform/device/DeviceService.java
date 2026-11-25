package de.tstieh.stoneintelligence.platform.device;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.audit.AuditRecorder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Plugin-Richtlinie und Geraeteregister eines Vaults (ADR 0019). */
@Service
public class DeviceService implements DeviceGate {

    static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final PluginPolicyRepository policies;
    private final DeviceRepository devices;
    private final AuditRecorder audit;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public DeviceService(PluginPolicyRepository policies, DeviceRepository devices, AuditRecorder audit, Clock clock,
                         ApplicationEventPublisher events) {
        this.policies = policies;
        this.devices = devices;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    public PluginPolicy policy(VaultId vaultId) {
        return policies.find(vaultId);
    }

    public PluginPolicy changePolicy(VaultId vaultId, String actor, PolicyMode mode, List<PluginRef> allowed) {
        var before = policies.find(vaultId);
        var after = new PluginPolicy(vaultId, mode, allowed);
        if (before.mode() == after.mode() && before.allowed().equals(after.allowed())) {
            return before;
        }
        policies.save(after, actor);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("from", before.mode().name());
        payload.put("to", after.mode().name());
        payload.put("allowed", after.allowed().stream().map(PluginRef::label).toList());
        audit.record(vaultId, null, actor, "plugin-policy.changed", payload);
        return after;
    }

    /** Antwort auf eine Meldung: Stand, Richtlinie und ob das Geraet seine lokalen Daten loeschen soll. */
    public record ReportResult(DeviceState state, PluginPolicy policy) {
        public boolean wipe() {
            return state.status() == DeviceState.Status.WIPE_PENDING;
        }
    }

    public ReportResult report(VaultId vaultId, String subject, String deviceId, DeviceReport report) {
        requireValidId(deviceId);
        var policy = policies.find(vaultId);
        var now = clock.instant();
        var before = devices.find(vaultId, deviceId);
        var beforeState = before.map(device -> DeviceState.of(policy, device)).orElse(null);
        var updated = before.map(device -> device.reported(subject, report, now))
            .orElseGet(() -> Device.first(vaultId, deviceId, subject, report, now));
        devices.save(updated);
        var state = DeviceState.of(policy, updated);

        if (before.isPresent() && before.get().wipePending() && updated.wipedAt() != null) {
            audit.record(vaultId, null, subject, "device.wiped", Map.of("deviceId", deviceId, "device", updated.name()));
        }
        if (state.status() == DeviceState.Status.NON_COMPLIANT
            && (beforeState == null || beforeState.status() != DeviceState.Status.NON_COMPLIANT
                || !Objects.equals(beforeState.offending(), state.offending()))) {
            audit.record(vaultId, null, subject, "device.non-compliant", Map.of("deviceId", deviceId,
                "device", updated.name(), "plugins", state.offending().stream().map(PluginRef::label).toList()));
            events.publishEvent(new DeviceRefused(vaultId, deviceId));
        }
        return new ReportResult(state, policy);
    }

    public List<Device> devices(VaultId vaultId) {
        return devices.list(vaultId);
    }

    public void revoke(VaultId vaultId, String actor, String deviceId) {
        change(vaultId, deviceId, device -> device.revoke(actor, clock.instant()), actor, "device.revoked");
    }

    public void wipe(VaultId vaultId, String actor, String deviceId) {
        change(vaultId, deviceId, device -> device.wipe(actor, clock.instant()), actor, "device.wipe-requested");
    }

    public void reinstate(VaultId vaultId, String actor, String deviceId) {
        var device = find(vaultId, deviceId);
        devices.save(device.reinstate());
        audit.record(vaultId, null, actor, "device.reinstated", Map.of("deviceId", deviceId, "device", device.name()));
    }

    private void change(VaultId vaultId, String deviceId, java.util.function.UnaryOperator<Device> change, String actor, String action) {
        var device = find(vaultId, deviceId);
        devices.save(change.apply(device));
        audit.record(vaultId, null, actor, action, Map.of("deviceId", deviceId, "device", device.name(), "person", device.subject()));
        events.publishEvent(new DeviceRefused(vaultId, deviceId));
    }

    private Device find(VaultId vaultId, String deviceId) {
        requireValidId(deviceId);
        return devices.find(vaultId, deviceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown device"));
    }

    @Override
    public void requireAllowed(VaultId vaultId, String deviceId) {
        var policy = policies.find(vaultId);
        var device = deviceId == null || !DEVICE_ID.matcher(deviceId).matches()
            ? null : devices.find(vaultId, deviceId).orElse(null);
        var state = DeviceState.of(policy, device);
        switch (state.status()) {
            case COMPLIANT -> { }
            case UNKNOWN -> throw new DeviceGateException("DEVICE_UNKNOWN",
                "Dieser Vault erlaubt nur gemeldete Geräte. Aktualisiere das Plugin und verbinde dich erneut.");
            case REVOKED -> throw new DeviceGateException("DEVICE_REVOKED",
                "Dieses Gerät wurde gesperrt. Wende dich an die Verwaltung des Vaults.");
            case WIPE_PENDING -> throw new DeviceGateException("DEVICE_WIPE",
                "Dieses Gerät wurde gesperrt, die Daten des Vaults werden hier gelöscht.");
            case NON_COMPLIANT -> throw new DeviceGateException("DEVICE_NOT_COMPLIANT",
                "Nicht erlaubte Plugins sind aktiv: "
                    + String.join(", ", state.offending().stream().map(PluginRef::label).toList())
                    + ". Deaktiviere sie, dann geht es weiter.");
        }
    }

    private static void requireValidId(String deviceId) {
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            throw new IllegalArgumentException("invalid device id");
        }
    }
}
