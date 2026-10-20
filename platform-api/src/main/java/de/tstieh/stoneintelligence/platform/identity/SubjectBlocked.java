package de.tstieh.stoneintelligence.platform.identity;

/** Der IdP hat das Konto deaktiviert oder geloescht (SCIM, ADR 0013): offene Verbindungen schliessen. */
public record SubjectBlocked(String subject) {
}
