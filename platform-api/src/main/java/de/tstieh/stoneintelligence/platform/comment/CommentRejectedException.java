package de.tstieh.stoneintelligence.platform.comment;

/** Anfrage fachlich abgelehnt; der Text ist für Menschen geschrieben und wird direkt angezeigt (422). */
public final class CommentRejectedException extends RuntimeException {

    public CommentRejectedException(String message) {
        super(message);
    }
}
