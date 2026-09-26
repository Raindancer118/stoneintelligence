package de.tstieh.stoneintelligence.platform.vault;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Geworfen, wenn ein authentifiziertes Subject zwar bekannt, aber fuer die angeforderte
 * Operation in diesem Vault nicht berechtigt ist (fehlende {@code Permission} aus
 * {@code AuthorizationRepository.effectivePermissions} oder eine Freigabe, die sie nimmt, s. {@link
 * de.tstieh.stoneintelligence.platform.identity.AccessResolver}). Anders als {@link
 * NoteNotFoundException} (Existenz) - hier steht die Note/der Vault fest, nur der Zugriff fehlt.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public final class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
