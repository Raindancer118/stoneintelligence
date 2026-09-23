package de.raindancer118.stoneintelligence.platform.vault;

import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/**
 * Ordner eines Vaults als eigene Objekte - sonst gaebe es leere Ordner auf dem Server gar nicht,
 * und ein geloeschter Ordner liesse sich auf anderen Geraeten nicht von einem nur geleerten
 * unterscheiden. Alle Operationen sind vault-gescopt und idempotent.
 */
public interface FolderRepository {

    /** Alle Ordnerpfade, sortiert. */
    List<String> list(VaultId vaultId);

    /** Legt den Ordner samt fehlender Elternordner an; liefert nur die tatsaechlich neuen. */
    List<String> ensure(VaultId vaultId, String path, String actor);

    /** Wer den Ordner angelegt hat (Mensch oder {@code ki:<Name>}). */
    java.util.Optional<String> creator(VaultId vaultId, String path);

    /** Loescht den Ordner und alles darunter; liefert die Anzahl. */
    int deleteTree(VaultId vaultId, String path);

    /** Verschiebt den Ordner samt allem darunter; bestehende Ziele werden zusammengefuehrt. */
    void renameTree(VaultId vaultId, String from, String to, String actor);
}
