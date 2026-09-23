package de.raindancer118.stoneintelligence.platform.files;

/** Der Dateispeicher (gehostet: Storage Box) ist gerade nicht erreichbar - Notizen betrifft das nicht. */
public class BlobStoreUnavailableException extends RuntimeException {

    public BlobStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
