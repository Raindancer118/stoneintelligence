package de.tstieh.stoneintelligence.platform.vault;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Namen von Vaults, Rollen und Gruppen: Nutzereingabe, die in Listen, Protokollen und Mails wieder auftaucht. */
public final class DisplayNames {

    static final int MAX_LENGTH = 200;

    private DisplayNames() {
    }

    public static String require(String name) {
        var stripped = name == null ? "" : name.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_LENGTH || stripped.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must have 1 to " + MAX_LENGTH + " printable characters");
        }
        return stripped;
    }
}
