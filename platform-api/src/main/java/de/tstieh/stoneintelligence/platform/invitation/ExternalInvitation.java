package de.tstieh.stoneintelligence.platform.invitation;

/** Einladung im Identity-Provider: dessen Id und der Link, ueber den das Konto angelegt wird. */
public record ExternalInvitation(String id, String enrollmentUrl) {
}
