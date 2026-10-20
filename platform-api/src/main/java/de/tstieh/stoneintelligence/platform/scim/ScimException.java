package de.tstieh.stoneintelligence.platform.scim;

import org.springframework.http.HttpStatus;

/** Ein Fehler im Format von RFC 7644 Abschnitt 3.12; {@code scimType} nur, wo die RFC einen vorsieht. */
public class ScimException extends RuntimeException {

    private final HttpStatus status;
    private final String scimType;

    public ScimException(HttpStatus status, String scimType, String detail) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
    }

    public static ScimException invalidFilter(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidFilter", detail);
    }

    public static ScimException invalidValue(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidValue", detail);
    }

    public static ScimException invalidSyntax(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidSyntax", detail);
    }

    public static ScimException invalidPath(String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, "invalidPath", detail);
    }

    public static ScimException uniqueness(String detail) {
        return new ScimException(HttpStatus.CONFLICT, "uniqueness", detail);
    }

    public static ScimException notFound(String resource) {
        return new ScimException(HttpStatus.NOT_FOUND, null, resource + " not found");
    }

    public HttpStatus status() {
        return status;
    }

    public String scimType() {
        return scimType;
    }
}
