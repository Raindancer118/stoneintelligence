package de.tstieh.stoneintelligence.platform.device;

import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Ein Geraet darf nicht mehr verbunden bleiben (gesperrt, Loeschauftrag oder gegen die Richtlinie): offene Verbindungen schliessen. */
public record DeviceRefused(VaultId vaultId, String deviceId) {
}
