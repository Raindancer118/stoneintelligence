package de.tstieh.stoneintelligence.platform.audit;

import java.time.Clock;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.ForbiddenException;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditRetentionControllerTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final HistoryControllerTest.FakeAudit audit = new HistoryControllerTest.FakeAudit();
    private final AuditRetentionController controller = new AuditRetentionController(
        new VaultAccessGuard(authorization, new FakeAccessGrantRepository(new FakeNoteRepository())),
        new AuditRetentionService(new FakeAuditRetentionRepository(), audit, Clock.systemUTC()));
    private final VaultId vaultId = VaultId.newId();
    private final TestingAuthenticationToken tom = new TestingAuthenticationToken("tom", null);
    private final TestingAuthenticationToken ben = new TestingAuthenticationToken("ben", null);

    @BeforeEach
    void members() {
        member("tom", Set.of(Permission.READ, Permission.MANAGE));
        member("ben", Set.of(Permission.READ, Permission.WRITE));
    }

    private void member(String subject, Set<Permission> permissions) {
        var role = authorization.createRole(vaultId, subject, permissions);
        var group = authorization.createGroup(vaultId, subject);
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), subject);
    }

    @Test
    void should_keepForeverByDefault_andLetManagersSetAPeriod() {
        var id = vaultId.value().toString();
        assertThat(controller.get(id, tom).days()).isNull();
        assertThat(controller.get(id, tom).minimumDays()).isEqualTo(365);

        var changed = controller.put(id, new AuditRetentionController.ChangeRequest(1095), tom);

        assertThat(changed.days()).isEqualTo(1095);
        assertThat(controller.get(id, tom).days()).isEqualTo(1095);
        assertThat(audit.events).extracting(AuditEvent::action).containsExactly("audit.retention-changed");
    }

    @Test
    void should_refuseEveryoneWithoutManagePermission() {
        var id = vaultId.value().toString();

        assertThatThrownBy(() -> controller.get(id, ben)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.put(id, new AuditRetentionController.ChangeRequest(365), ben)).isInstanceOf(ForbiddenException.class);
        assertThat(audit.events).isEmpty();
    }
}
