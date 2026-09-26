package de.tstieh.stoneintelligence.stoneai.extract;

/** Raised when a model answer cannot be turned into concepts. */
public class ExtractionException extends RuntimeException {

    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
