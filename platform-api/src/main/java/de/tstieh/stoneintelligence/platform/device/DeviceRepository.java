package de.tstieh.stoneintelligence.platform.device;

import java.util.List;
import java.util.Optional;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public interface DeviceRepository {

    Optional<Device> find(VaultId vaultId, String deviceId);

    /** Neu anlegen oder den Stand ersetzen. */
    void save(Device device);

    /** Zuletzt gesehene zuerst. */
    List<Device> list(VaultId vaultId);
}
