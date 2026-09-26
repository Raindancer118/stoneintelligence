package de.tstieh.stoneintelligence.platform.files;

/** Fachlich abgelehnt (kein Datei-Eintrag, unzulaessiger Pfad oder Level) - der Text ist fuer Menschen. */
public class FileRefusedException extends RuntimeException {

    public FileRefusedException(String message) {
        super(message);
    }
}
