package de.tstieh.stoneintelligence.platform.device;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import de.tstieh.stoneintelligence.domain.id.VaultId;

class FakeDeviceRepository implements DeviceRepository {

    private final Map<String, Device> devices = new HashMap<>();

    @Override
    public Optional<Device> find(VaultId vaultId, String deviceId) {
        return Optional.ofNullable(devices.get(vaultId.value() + "/" + deviceId));
    }

    @Override
    public void save(Device device) {
        devices.put(device.vaultId().value() + "/" + device.deviceId(), device);
    }

    @Override
    public List<Device> list(VaultId vaultId) {
        return new ArrayList<>(devices.values()).stream()
            .filter(device -> device.vaultId().equals(vaultId))
            .sorted(Comparator.comparing(Device::lastSeen).reversed())
            .toList();
    }
}
