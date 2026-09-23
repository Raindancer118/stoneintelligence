package de.raindancer118.stoneintelligence.platform.invitation;

/** Ein Konto im Identity-Provider (Authentik), wie es fuer Einladungen gebraucht wird. */
public record DirectoryUser(String username, String name, String email) {
}
