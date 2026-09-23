package de.raindancer118.stoneintelligence.platform.invitation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Fachlich abgelehnte Einladung (ungueltige Adresse, abgelaufen, schon benutzt ...) - Text ist fuer Menschen. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class InvitationException extends RuntimeException {

    public InvitationException(String message) {
        super(message);
    }
}
