package de.tstieh.stoneintelligence.platform.files;

public class BlobNotFoundException extends RuntimeException {

    public BlobNotFoundException(String sha256) {
        super("Inhalt " + sha256 + " nicht vorhanden");
    }
}
