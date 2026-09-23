package de.raindancer118.stoneintelligence.platform.invitation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Ohne Authentik-API-Token: keine Suche, keine Authentik-Einladung - Einladungslinks per Mail gehen trotzdem. */
public class DisabledUserDirectory implements UserDirectory {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<DirectoryUser> search(String query, int limit) {
        return List.of();
    }

    @Override
    public Optional<DirectoryUser> findByEmail(String email) {
        return Optional.empty();
    }

    @Override
    public Optional<DirectoryUser> findByUsername(String username) {
        return Optional.empty();
    }

    @Override
    public ExternalInvitation createInvitation(String email, Instant expiresAt) {
        throw new UnsupportedOperationException("Authentik ist nicht angebunden");
    }

    @Override
    public void deleteInvitation(String id) {
        // Nichts angelegt, nichts zu loeschen.
    }
}
