package de.tstieh.stoneintelligence.platform.vault;

import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Diesen Ordner gibt es im Vault nicht (404, wie {@link NoteNotFoundException}). */
@ResponseStatus(HttpStatus.NOT_FOUND)
public final class FolderNotFoundException extends RuntimeException {

    public FolderNotFoundException(VaultId vaultId, String path) {
        super("no folder '" + path + "' in vault " + vaultId.value());
    }
}
