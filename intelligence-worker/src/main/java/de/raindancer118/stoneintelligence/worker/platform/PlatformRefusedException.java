package de.raindancer118.stoneintelligence.worker.platform;

/** platform-api hat eine Anfrage fachlich abgelehnt (4xx) - erneut versuchen aendert daran nichts. */
public class PlatformRefusedException extends RuntimeException {

    public PlatformRefusedException(String message) {
        super(message);
    }
}
