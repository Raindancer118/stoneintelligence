package de.tstieh.stoneintelligence.platform.files;

/** Die Aenderung beruht nicht auf der aktuellen Fassung - jemand anderes war schneller (ADR 0009 Punkt 3). */
public class FileRevisionConflictException extends RuntimeException {

    private final long currentRevision;

    public FileRevisionConflictException(long currentRevision) {
        super("Die Datei wurde inzwischen geändert (aktuelle Fassung " + currentRevision + ")");
        this.currentRevision = currentRevision;
    }

    public long currentRevision() {
        return currentRevision;
    }
}
