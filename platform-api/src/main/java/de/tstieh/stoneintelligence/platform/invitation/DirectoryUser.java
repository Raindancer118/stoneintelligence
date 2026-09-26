package de.tstieh.stoneintelligence.platform.invitation;

/** Ein Konto im Identity-Provider (Authentik), wie es fuer Einladungen gebraucht wird. */
public record DirectoryUser(String username, String name, String email) {
}
