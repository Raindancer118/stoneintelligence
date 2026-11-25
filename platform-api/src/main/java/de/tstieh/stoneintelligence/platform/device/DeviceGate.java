package de.tstieh.stoneintelligence.platform.device;

import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Wer synchronisieren darf, entscheidet die Plugin-Richtlinie des Vaults (ADR 0019). */
public interface DeviceGate {

    /** Ohne Beschraenkung, solange der Vault keine Richtlinie durchsetzt (fuer Tests und aeltere Aufbauten). */
    DeviceGate OPEN = (vaultId, deviceId) -> { };

    /** @throws DeviceGateException mit Code und deutscher Meldung fuer das Plugin; {@code deviceId} darf fehlen. */
    void requireAllowed(VaultId vaultId, String deviceId);
}
