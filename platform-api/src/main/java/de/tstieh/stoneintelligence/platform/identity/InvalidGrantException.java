package de.tstieh.stoneintelligence.platform.identity;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Eine Freigabe oder Verwaltungsaenderung, die so nicht geht (unbekannte Person, fremde Gruppe, ...). */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public final class InvalidGrantException extends RuntimeException {

    public InvalidGrantException(String message) {
        super(message);
    }
}
