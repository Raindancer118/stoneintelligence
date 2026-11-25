package de.tstieh.stoneintelligence.platform.comment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Kommentar oder Thread gibt es nicht - oder nicht in diesem Vault (dann ununterscheidbar, wie bei Notizen). */
@ResponseStatus(HttpStatus.NOT_FOUND)
public final class CommentNotFoundException extends RuntimeException {

    public CommentNotFoundException(String message) {
        super(message);
    }
}
