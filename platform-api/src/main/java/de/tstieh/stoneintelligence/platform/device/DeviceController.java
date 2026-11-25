package de.tstieh.stoneintelligence.platform.device;

import java.time.Instant;
import java.util.List;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.PeopleDirectory;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

/**
 * Plugin-Richtlinie und Geraete (ADR 0019). Lesen der Richtlinie und das Melden des eigenen Geraets darf
 * jedes Mitglied; Richtlinie aendern, Geraeteliste, Sperren und Loeschen verlangen Verwalten.
 */
@RestController
public class DeviceController {

    private final VaultAccessGuard access;
    private final DeviceService service;
    private final PeopleDirectory people;

    public DeviceController(VaultAccessGuard access, DeviceService service, PeopleDirectory people) {
        this.access = access;
        this.service = service;
        this.people = people;
    }

    @GetMapping("/api/v1/vaults/{vaultId}/plugin-policy")
    public PolicyResponse policy(@PathVariable String vaultId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.requireMember(vId, auth.getName());
        return PolicyResponse.of(service.policy(vId));
    }

    @PutMapping("/api/v1/vaults/{vaultId}/plugin-policy")
    public PolicyResponse changePolicy(@PathVariable String vaultId, @RequestBody PolicyRequest request, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.MANAGE);
        if (request.mode() == null || request.allowed() == null) {
            throw new IllegalArgumentException("mode and allowed are required");
        }
        var allowed = request.allowed().stream().map(rule -> new PluginRef(rule.id(), blankToNull(rule.version()))).toList();
        return PolicyResponse.of(service.changePolicy(vId, auth.getName(), request.mode(), allowed));
    }

    @PutMapping("/api/v1/vaults/{vaultId}/devices/{deviceId}")
    public ReportResponse report(@PathVariable String vaultId, @PathVariable String deviceId,
                                 @RequestBody ReportRequest request, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.requireMember(vId, auth.getName());
        var plugins = request.plugins() == null ? null
            : request.plugins().stream().map(p -> new PluginRef(p.id(), blankToNull(p.version()))).toList();
        var result = service.report(vId, auth.getName(), deviceId, new DeviceReport(request.name(), request.platform(),
            request.obsidianVersion(), request.pluginVersion(), request.restrictedMode(), plugins, request.wiped()));
        return new ReportResponse(result.state().status().name(), result.state().compliant(),
            result.state().offending().stream().map(PluginRef::label).toList(), result.wipe(), PolicyResponse.of(result.policy()));
    }

    @GetMapping("/api/v1/vaults/{vaultId}/devices")
    public List<DeviceView> devices(@PathVariable String vaultId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.MANAGE);
        var policy = service.policy(vId);
        var devices = service.devices(vId);
        var names = people.displayNames(devices.stream().map(Device::subject).distinct().toList());
        return devices.stream().map(device -> DeviceView.of(device, DeviceState.of(policy, device),
            names.getOrDefault(device.subject(), device.subject()))).toList();
    }

    @PostMapping("/api/v1/vaults/{vaultId}/devices/{deviceId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String vaultId, @PathVariable String deviceId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.MANAGE);
        service.revoke(vId, auth.getName(), deviceId);
    }

    @PostMapping("/api/v1/vaults/{vaultId}/devices/{deviceId}/wipe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void wipe(@PathVariable String vaultId, @PathVariable String deviceId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.MANAGE);
        service.wipe(vId, auth.getName(), deviceId);
    }

    @PostMapping("/api/v1/vaults/{vaultId}/devices/{deviceId}/reinstate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reinstate(@PathVariable String vaultId, @PathVariable String deviceId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.MANAGE);
        service.reinstate(vId, auth.getName(), deviceId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record RuleDto(String id, String version) {
    }

    public record PolicyRequest(PolicyMode mode, List<RuleDto> allowed) {
    }

    public record PolicyResponse(PolicyMode mode, List<RuleDto> allowed, String alwaysAllowed) {
        static PolicyResponse of(PluginPolicy policy) {
            return new PolicyResponse(policy.mode(),
                policy.allowed().stream().map(rule -> new RuleDto(rule.id(), rule.version())).toList(),
                PluginPolicy.OWN_PLUGIN_ID);
        }
    }

    public record ReportRequest(String name, String platform, String obsidianVersion, String pluginVersion,
                                boolean restrictedMode, List<RuleDto> plugins, boolean wiped) {
    }

    /** {@code wipe}: das Geraet loescht seine lokale Kopie und meldet danach {@code wiped}. */
    public record ReportResponse(String status, boolean compliant, List<String> offending, boolean wipe, PolicyResponse policy) {
    }

    public record DeviceView(String deviceId, String subject, String person, String name, String platform,
                             String obsidianVersion, String pluginVersion, boolean restrictedMode, List<RuleDto> plugins,
                             Instant firstSeen, Instant lastSeen, String status, boolean compliant, List<String> offending,
                             boolean revoked, boolean wipePending, Instant wipedAt) {
        static DeviceView of(Device device, DeviceState state, String person) {
            return new DeviceView(device.deviceId(), device.subject(), person, device.name(), device.platform(),
                device.obsidianVersion(), device.pluginVersion(), device.restrictedMode(),
                device.plugins().stream().map(p -> new RuleDto(p.id(), p.version())).toList(),
                device.firstSeen(), device.lastSeen(), state.status().name(), state.compliant(),
                state.offending().stream().map(PluginRef::label).toList(), device.revoked(), device.wipePending(), device.wipedAt());
        }
    }
}
