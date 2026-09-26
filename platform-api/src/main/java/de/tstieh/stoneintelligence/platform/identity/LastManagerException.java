package de.tstieh.stoneintelligence.platform.identity;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Die Aenderung liesse niemanden zurueck, der den Vault verwalten kann (ADR 0011, "nicht aussperren"). */
@ResponseStatus(HttpStatus.CONFLICT)
public final class LastManagerException extends RuntimeException {

    public LastManagerException() {
        super("after this change nobody could manage the vault any more");
    }
}
