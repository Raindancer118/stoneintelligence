package de.tstieh.stoneintelligence.platform.device;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.audit.AuditEvent;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.FakePeopleDirectory;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.ForbiddenException;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceControllerTest {

    private static final String DEVICE = "dev-12345678";

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final List<AuditEvent> audit = new ArrayList<>();
    private final FakePeopleDirectory people = new FakePeopleDirectory();
    private final DeviceController controller = new DeviceController(
        new VaultAccessGuard(authorization, new FakeAccessGrantRepository(new FakeNoteRepository())),
        new DeviceService(new FakePluginPolicyRepository(), new FakeDeviceRepository(),
            (vaultId, noteId, actor, action, payload) -> audit.add(new AuditEvent(null, vaultId, noteId, actor, action, payload, null)),
            Clock.systemUTC(), event -> { }),
        people);
    private final VaultId vaultId = VaultId.newId();
    private final String id = vaultId.value().toString();
    private final TestingAuthenticationToken admin = new TestingAuthenticationToken("admin", null);
    private final TestingAuthenticationToken ben = new TestingAuthenticationToken("ben", null);
    private final TestingAuthenticationToken outsider = new TestingAuthenticationToken("outsider", null);

    @BeforeEach
    void members() {
        member("admin", Set.of(Permission.READ, Permission.MANAGE));
        member("ben", Set.of(Permission.READ, Permission.WRITE));
    }

    private void member(String subject, Set<Permission> permissions) {
        var role = authorization.createRole(vaultId, subject, permissions);
        var group = authorization.createGroup(vaultId, subject);
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), subject);
    }

    private static DeviceController.ReportRequest report(String... pluginIds) {
        return new DeviceController.ReportRequest("Laptop", "linux", "1.15.0", "0.34.0", false,
            java.util.Arrays.stream(pluginIds).map(p -> new DeviceController.RuleDto(p, "1.0.0")).toList(), false);
    }

    @Test
    void should_letMembersReadThePolicy_butOnlyManagersChangeIt() {
        assertThat(controller.policy(id, ben).mode()).isEqualTo(PolicyMode.OFF);
        assertThat(controller.policy(id, ben).alwaysAllowed()).isEqualTo("stoneintelligence");

        var changed = controller.changePolicy(id, new DeviceController.PolicyRequest(PolicyMode.ENFORCE,
            List.of(new DeviceController.RuleDto("calendar", " "), new DeviceController.RuleDto("dataview", "0.5.68"))), admin);

        assertThat(changed.mode()).isEqualTo(PolicyMode.ENFORCE);
        assertThat(changed.allowed()).containsExactly(new DeviceController.RuleDto("calendar", null), new DeviceController.RuleDto("dataview", "0.5.68"));
        assertThatThrownBy(() -> controller.changePolicy(id, new DeviceController.PolicyRequest(PolicyMode.OFF, List.of()), ben))
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.policy(id, outsider)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void should_answerAReportWithTheStateAndOffendingPlugins() {
        controller.changePolicy(id, new DeviceController.PolicyRequest(PolicyMode.ENFORCE, List.of(new DeviceController.RuleDto("calendar", null))), admin);

        var response = controller.report(id, DEVICE, report("calendar", "kanban"), ben);

        assertThat(response.status()).isEqualTo("NON_COMPLIANT");
        assertThat(response.compliant()).isFalse();
        assertThat(response.offending()).containsExactly("kanban 1.0.0");
        assertThat(response.wipe()).isFalse();
        assertThat(response.policy().mode()).isEqualTo(PolicyMode.ENFORCE);
        assertThatThrownBy(() -> controller.report(id, DEVICE, report(), outsider)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void should_listDevicesForManagersOnly_withPersonAndCompliance() {
        people.remember("ben", "Ben Beispiel", java.time.Instant.now());
        controller.changePolicy(id, new DeviceController.PolicyRequest(PolicyMode.ENFORCE, List.of()), admin);
        controller.report(id, DEVICE, report("kanban"), ben);

        var list = controller.devices(id, admin);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).person()).isEqualTo("Ben Beispiel");
        assertThat(list.get(0).compliant()).isFalse();
        assertThat(list.get(0).offending()).containsExactly("kanban 1.0.0");
        assertThatThrownBy(() -> controller.devices(id, ben)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void should_revokeWipeAndReinstate_forManagersOnly() {
        controller.report(id, DEVICE, report(), ben);

        assertThatThrownBy(() -> controller.revoke(id, DEVICE, ben)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.wipe(id, DEVICE, ben)).isInstanceOf(ForbiddenException.class);

        controller.revoke(id, DEVICE, admin);
        assertThat(controller.devices(id, admin).get(0).status()).isEqualTo("REVOKED");
        controller.wipe(id, DEVICE, admin);
        assertThat(controller.devices(id, admin).get(0).status()).isEqualTo("WIPE_PENDING");
        assertThat(controller.report(id, DEVICE, report(), ben).wipe()).isTrue();
        controller.reinstate(id, DEVICE, admin);
        assertThat(controller.devices(id, admin).get(0).status()).isEqualTo("COMPLIANT");
        assertThat(audit).extracting(AuditEvent::action).containsExactly("device.revoked", "device.wipe-requested", "device.reinstated");
    }

    @Test
    void should_rejectABadReport() {
        assertThatThrownBy(() -> controller.report(id, DEVICE, new DeviceController.ReportRequest("", "linux", "1", "1", false, List.of(), false), ben))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.report(id, DEVICE, new DeviceController.ReportRequest("L", "linux", "1", "1", false, null, false), ben))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
