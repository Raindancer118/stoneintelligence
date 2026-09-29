package de.tstieh.stoneintelligence.platform.identity;

import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Zaehlt jede Aenderung an Freigaben, Rollen, Gruppen und Mitgliedschaften eines Vaults (Trigger, V19). */
@FunctionalInterface
public interface AccessVersions {

    long current(VaultId vaultId);
}
