package de.raindancer118.stoneintelligence.platform.vault;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Geworfen, wenn ein authentifiziertes Subject zwar bekannt, aber fuer die angeforderte
 * Operation in diesem Vault nicht berechtigt ist (fehlende {@code Permission} aus
 * {@code AuthorizationRepository.effectivePermissions} oder DENY aus {@link
 * de.raindancer118.stoneintelligence.platform.identity.PathRules}). Anders als {@link
 * NoteNotFoundException} (Existenz) - hier steht die Note/der Vault fest, nur der Zugriff fehlt.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public final class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
