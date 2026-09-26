package de.tstieh.stoneintelligence.platform.files;

public class BlobTooLargeException extends RuntimeException {

    private final long limit;

    public BlobTooLargeException(long limit) {
        super("Die Datei ist größer als " + limit / (1024 * 1024) + " MB");
        this.limit = limit;
    }

    public long limit() {
        return limit;
    }
}
