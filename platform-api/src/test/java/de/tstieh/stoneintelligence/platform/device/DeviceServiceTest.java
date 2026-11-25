package de.tstieh.stoneintelligence.platform.device;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceServiceTest {

    private static final String DEVICE = "dev-12345678";

    private final VaultId vault = VaultId.newId();
    private final FakePluginPolicyRepository policies = new FakePluginPolicyRepository();
    private final FakeDeviceRepository devices = new FakeDeviceRepository();
    private final List<AuditEvent> audit = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();
    private final DeviceService service = new DeviceService(policies, devices,
        (vaultId, noteId, actor, action, payload) -> audit.add(new AuditEvent(null, vaultId, noteId, actor, action, payload, Instant.EPOCH)),
        Clock.fixed(Instant.parse("2026-11-25T09:00:00Z"), ZoneOffset.UTC), events::add);

    private static DeviceReport report(boolean wiped, PluginRef... plugins) {
        return new DeviceReport("Laptop", "linux", "1.15.0", "0.34.0", false, List.of(plugins), wiped);
    }

    private void enforce(PluginRef... allowed) {
        service.changePolicy(vault, "admin", PolicyMode.ENFORCE, List.of(allowed));
        audit.clear();
    }

    @Test
    void should_letEveryoneThrough_whenThePolicyIsOff() {
        service.requireAllowed(vault, null);
        service.report(vault, "tom", DEVICE, report(false, new PluginRef("anything", "1")));
        service.requireAllowed(vault, DEVICE);
    }

    @Test
    void should_refuseUnknownDevices_andThoseWithoutId_whenEnforcing() {
        enforce();

        assertThatThrownBy(() -> service.requireAllowed(vault, null)).isInstanceOfSatisfying(DeviceGateException.class,
            e -> assertThat(e.code()).isEqualTo("DEVICE_UNKNOWN"));
        assertThatThrownBy(() -> service.requireAllowed(vault, DEVICE)).isInstanceOfSatisfying(DeviceGateException.class,
            e -> assertThat(e.code()).isEqualTo("DEVICE_UNKNOWN"));
    }

    @Test
    void should_allowOwnPluginAndAllowedOnes_andNameTheOffendingOnes() {
        enforce(new PluginRef("calendar", "1.5.10"), new PluginRef("dataview", null));

        var ok = service.report(vault, "tom", DEVICE, report(false, new PluginRef("stoneintelligence", "0.34.0"),
            new PluginRef("calendar", "1.5.10"), new PluginRef("dataview", "0.5.68")));
        assertThat(ok.state().compliant()).isTrue();
        service.requireAllowed(vault, DEVICE);

        var bad = service.report(vault, "tom", DEVICE, report(false, new PluginRef("calendar", "1.5.9"), new PluginRef("kanban", "2.0.51")));
        assertThat(bad.state().status()).isEqualTo(DeviceState.Status.NON_COMPLIANT);
        assertThat(bad.state().offending()).extracting(PluginRef::label).containsExactly("calendar 1.5.9", "kanban 2.0.51");
        assertThatThrownBy(() -> service.requireAllowed(vault, DEVICE)).isInstanceOfSatisfying(DeviceGateException.class, e -> {
            assertThat(e.code()).isEqualTo("DEVICE_NOT_COMPLIANT");
            assertThat(e.getMessage()).contains("calendar 1.5.9").contains("kanban 2.0.51");
        });
    }

    @Test
    void should_auditNonComplianceOncePerChange_andCloseOpenConnections() {
        enforce(new PluginRef("calendar", null));
        service.report(vault, "tom", DEVICE, report(false, new PluginRef("kanban", "1")));
        service.report(vault, "tom", DEVICE, report(false, new PluginRef("kanban", "1")));
        service.report(vault, "tom", DEVICE, report(false, new PluginRef("kanban", "1"), new PluginRef("x", "2")));

        assertThat(audit).extracting(AuditEvent::action).containsExactly("device.non-compliant", "device.non-compliant");
        assertThat(events).hasSize(2).allSatisfy(e -> assertThat(e).isEqualTo(new DeviceRefused(vault, DEVICE)));
    }

    @Test
    void should_becomeCompliantAgain_afterDisablingThePlugins() {
        enforce();
        service.report(vault, "tom", DEVICE, report(false, new PluginRef("kanban", "1")));

        var fixed = service.report(vault, "tom", DEVICE, report(false));

        assertThat(fixed.state().compliant()).isTrue();
        service.requireAllowed(vault, DEVICE);
    }

    @Test
    void should_refuseARevokedDevice_evenWithoutEnforcement_andReinstateIt() {
        service.report(vault, "tom", DEVICE, report(false));

        service.revoke(vault, "admin", DEVICE);

        assertThatThrownBy(() -> service.requireAllowed(vault, DEVICE)).isInstanceOfSatisfying(DeviceGateException.class,
            e -> assertThat(e.code()).isEqualTo("DEVICE_REVOKED"));
        assertThat(audit).extracting(AuditEvent::action).containsExactly("device.revoked");
        assertThat(events).containsExactly(new DeviceRefused(vault, DEVICE));

        service.reinstate(vault, "admin", DEVICE);
        service.requireAllowed(vault, DEVICE);
        assertThat(audit).extracting(AuditEvent::action).containsExactly("device.revoked", "device.reinstated");
    }

    @Test
    void should_orderAWipe_untilTheDeviceConfirmsIt_thenKeepItRevoked() {
        service.report(vault, "tom", DEVICE, report(false));

        service.wipe(vault, "admin", DEVICE);
        var pending = service.report(vault, "tom", DEVICE, report(false));
        assertThat(pending.wipe()).isTrue();
        assertThatThrownBy(() -> service.requireAllowed(vault, DEVICE)).isInstanceOfSatisfying(DeviceGateException.class,
            e -> assertThat(e.code()).isEqualTo("DEVICE_WIPE"));

        var confirmed = service.report(vault, "tom", DEVICE, report(true));
        assertThat(confirmed.wipe()).isFalse();
        assertThat(confirmed.state().status()).isEqualTo(DeviceState.Status.REVOKED);
        assertThat(audit).extracting(AuditEvent::action).containsExactly("device.wipe-requested", "device.wiped");
        assertThat(devices.find(vault, DEVICE).orElseThrow().wipedAt()).isNotNull();
    }

    @Test
    void should_ignoreAWipeConfirmation_thatWasNeverOrdered() {
        service.report(vault, "tom", DEVICE, report(true));

        assertThat(devices.find(vault, DEVICE).orElseThrow().wipedAt()).isNull();
        assertThat(audit).isEmpty();
    }

    @Test
    void should_keepARevokedDeviceRevoked_whenAnotherPersonReportsFromIt() {
        service.report(vault, "tom", DEVICE, report(false));
        service.revoke(vault, "admin", DEVICE);

        service.report(vault, "ben", DEVICE, report(false));

        assertThat(devices.find(vault, DEVICE).orElseThrow().subject()).isEqualTo("ben");
        assertThatThrownBy(() -> service.requireAllowed(vault, DEVICE)).isInstanceOf(DeviceGateException.class);
    }

    @Test
    void should_auditPolicyChanges_andSkipUnchangedOnes() {
        service.changePolicy(vault, "admin", PolicyMode.ENFORCE, List.of(new PluginRef("calendar", "1.5.10")));
        service.changePolicy(vault, "admin", PolicyMode.ENFORCE, List.of(new PluginRef("calendar", "1.5.10")));

        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).action()).isEqualTo("plugin-policy.changed");
        assertThat(audit.get(0).payload()).containsEntry("to", "ENFORCE").containsEntry("allowed", List.of("calendar 1.5.10"));
    }

    @Test
    void should_rejectInvalidInput() {
        assertThatThrownBy(() -> service.report(vault, "tom", "x", report(false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginRef("../etc", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginPolicy(vault, PolicyMode.ENFORCE, List.of(new PluginRef("a", null), new PluginRef("a", "1"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.revoke(vault, "admin", DEVICE)).isInstanceOf(ResponseStatusException.class);
    }
}
