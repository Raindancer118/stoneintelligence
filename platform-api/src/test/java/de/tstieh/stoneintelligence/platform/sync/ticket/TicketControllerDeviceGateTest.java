package de.tstieh.stoneintelligence.platform.sync.ticket;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.device.DeviceGateException;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR 0019: kein Ticket fuer Geraete, die die Plugin-Richtlinie nicht erfuellt; die Kennung reicht bis in den Handshake. */
class TicketControllerDeviceGateTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final TicketService tickets = new TicketService(
        new MutableClock(Instant.parse("2026-11-25T09:00:00Z"), ZoneOffset.UTC), Duration.ofSeconds(30), new InMemoryTicketStore());
    private final VaultId vaultId = VaultId.newId();
    private final TestingAuthenticationToken tom = new TestingAuthenticationToken("tom", null);

    private TicketController controller(de.tstieh.stoneintelligence.platform.device.DeviceGate gate) {
        var role = authorization.createRole(vaultId, "r", Set.of(Permission.READ));
        var group = authorization.createGroup(vaultId, "g");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "tom");
        return new TicketController(tickets,
            new VaultAccessGuard(authorization, new FakeAccessGrantRepository(new FakeNoteRepository())), gate);
    }

    @Test
    void should_putTheDeviceIdIntoTheTicket_whenTheGateAllows() {
        var controller = controller((vault, deviceId) -> { });

        var issued = controller.issueTicket(vaultId.value().toString(), tom, "dev-12345678");

        assertThat(tickets.redeem(issued.token())).hasValueSatisfying(claims -> assertThat(claims.deviceId()).isEqualTo("dev-12345678"));
    }

    @Test
    void should_issueNoTicket_whenTheGateRefuses() {
        var controller = controller((vault, deviceId) -> {
            throw new DeviceGateException("DEVICE_NOT_COMPLIANT", "kanban aktiv");
        });

        assertThatThrownBy(() -> controller.issueTicket(vaultId.value().toString(), tom, "dev-12345678"))
            .isInstanceOfSatisfying(DeviceGateException.class, e -> assertThat(e.code()).isEqualTo("DEVICE_NOT_COMPLIANT"));
    }
}
