package de.tstieh.stoneintelligence.platform.identity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Speicher der Freigaben je Ordner und Eintrag (ADR 0011). Auswertung: {@link AccessResolver}. */
public interface AccessGrantRepository {

    /** Alle Freigaben des Vaults; Eintrags-Freigaben mit dem aktuellen Pfad ihres Eintrags. */
    List<AccessGrant> list(VaultId vaultId);

    /**
     * Setzt die Freigabe fuer dieses Ziel und diesen Wer (ersetzt eine vorhandene).
     * {@code permissions == null} heisst "wie im Vault".
     */
    AccessGrant put(VaultId vaultId, GrantTarget target, GrantScope scope, Set<Permission> permissions, String actor);

    /** Entfernt die Freigabe fuer dieses Ziel und diesen Wer; {@code false}, wenn es keine gab. */
    boolean remove(VaultId vaultId, GrantTarget target, GrantScope scope);

    /** Ordner umbenannt/verschoben: dessen Freigaben und die aller Unterordner wandern mit. */
    void moveFolder(VaultId vaultId, String from, String to);

    /** Ordner geloescht: dessen Freigaben und die aller Unterordner fallen weg. */
    void removeFolder(VaultId vaultId, String folder);

    /** Person hat den Vault verlassen: ihre persoenlichen Freigaben fallen weg. */
    void removeSubject(VaultId vaultId, String subject);

    /** Gruppe geloescht: ihre Freigaben fallen weg. */
    void removeGroup(VaultId vaultId, UUID groupId);
}
