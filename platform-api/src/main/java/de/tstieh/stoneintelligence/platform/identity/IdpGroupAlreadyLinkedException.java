package de.tstieh.stoneintelligence.platform.identity;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Im Vault ist schon eine andere Gruppe mit dieser IdP-Gruppe verknuepft. */
@ResponseStatus(HttpStatus.CONFLICT)
public class IdpGroupAlreadyLinkedException extends RuntimeException {

    public IdpGroupAlreadyLinkedException(String idpGroup) {
        super("IdP group '" + idpGroup + "' is already linked to another group of this vault");
    }
}
