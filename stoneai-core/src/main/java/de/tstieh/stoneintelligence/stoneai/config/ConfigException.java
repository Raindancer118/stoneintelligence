package de.tstieh.stoneintelligence.stoneai.config;

/** Raised when configuration is malformed, unknown or outside the allowed range. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
