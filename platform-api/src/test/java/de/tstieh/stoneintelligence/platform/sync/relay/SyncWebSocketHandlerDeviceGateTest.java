package de.tstieh.stoneintelligence.platform.sync.relay;

import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.device.DeviceGate;
import de.tstieh.stoneintelligence.platform.device.DeviceGateException;
import de.tstieh.stoneintelligence.platform.device.DeviceRefused;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.NoteActivityTracker;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0019: gesperrte Geraete bekommen keine Verbindung, und eine offene endet, sobald das Geraet gesperrt wird. */
class SyncWebSocketHandlerDeviceGateTest {

    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final Set<String> refusedDevices = new java.util.HashSet<>();
    private final DeviceGate gate = (vaultId, deviceId) -> {
        if (deviceId == null || refusedDevices.contains(deviceId)) {
            throw new DeviceGateException("DEVICE_REVOKED", "gesperrt");
        }
    };
    private final SyncWebSocketHandler handler = new SyncWebSocketHandler(
        new SyncRelayService(new FakeSnapshotStore(), new SyncRoomRegistry()), notes,
        new VaultAccessGuard(new FakeAuthorizationRepository(), new FakeAccessGrantRepository(notes)),
        new VaultAnnouncementService(), new NoteActivityTracker(notes),
        new de.tstieh.stoneintelligence.platform.identity.BlockedSubjects(Set::of, event -> { }), gate);
    private final VaultId vaultId = VaultId.newId();

    private FakeWebSocketSession connect(String deviceId) {
        var session = new FakeWebSocketSession();
        session.getAttributes().put(TicketHandshakeInterceptor.ATTR_VAULT_ID, vaultId);
        session.getAttributes().put(TicketHandshakeInterceptor.ATTR_ACTOR, "tom");
        if (deviceId != null) {
            session.getAttributes().put(TicketHandshakeInterceptor.ATTR_DEVICE_ID, deviceId);
        }
        handler.afterConnectionEstablished(session);
        return session;
    }

    @Test
    void should_closeAtOnce_whenTheTicketsDeviceIsRefused() {
        refusedDevices.add("dev-refused");

        var refused = connect("dev-refused");

        assertThat(refused.isOpen()).isFalse();
        assertThat(refused.closeStatus.getCode()).isEqualTo(SyncWebSocketHandler.CLOSE_DEVICE_REFUSED);
    }

    @Test
    void should_closeAConnectionWithoutDeviceId_whenTheGateRequiresOne() {
        assertThat(connect(null).isOpen()).isFalse();
    }

    @Test
    void should_closeOpenConnectionsOfADevice_whenItGetsRefused() {
        var mine = connect("dev-mine");
        var other = connect("dev-other");

        handler.onDeviceRefused(new DeviceRefused(vaultId, "dev-mine"));

        assertThat(mine.isOpen()).isFalse();
        assertThat(mine.closeStatus.getCode()).isEqualTo(4403);
        assertThat(other.isOpen()).isTrue();
    }

    @Test
    void should_notTouchConnectionsOfAnotherVaultWithTheSameDeviceId() {
        var mine = connect("dev-mine");

        handler.onDeviceRefused(new DeviceRefused(VaultId.newId(), "dev-mine"));

        assertThat(mine.isOpen()).isTrue();
    }
}
