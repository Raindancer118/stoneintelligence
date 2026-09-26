package de.tstieh.stoneintelligence.platform.vault;

import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Geworfen, wenn eine {@link NoteId} in einem gegebenen Vault nicht existiert - entweder weil
 * sie nie existiert hat, oder weil sie zu einem ANDEREN Vault gehoert. Beide Faelle muessen
 * identisch (404, keine Unterscheidung nach aussen) behandelt werden: sonst koennte ein Client
 * per Trial-and-Error herausfinden, ob eine bestimmte NoteId in einem fremden Vault existiert
 * (Mandanten-Isolation, Plan.md Abschnitt 8.2).
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public final class NoteNotFoundException extends RuntimeException {

    public NoteNotFoundException(VaultId vaultId, NoteId noteId) {
        super("no note " + noteId.value() + " in vault " + vaultId.value());
    }
}
