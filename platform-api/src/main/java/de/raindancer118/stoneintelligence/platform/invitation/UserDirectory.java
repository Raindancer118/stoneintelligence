package de.raindancer118.stoneintelligence.platform.invitation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port zum Identity-Provider. Authentik bleibt die einzige Quelle fuer Konten (kein eigener
 * Nutzerspeicher, s. V3__roles_and_groups.sql) - dieser Port liest nur und legt Einladungen an.
 */
public interface UserDirectory {

    /** false, solange kein API-Zugang konfiguriert ist - Einladungen per Link gehen dann trotzdem. */
    boolean isAvailable();

    List<DirectoryUser> search(String query, int limit);

    Optional<DirectoryUser> findByEmail(String email);

    Optional<DirectoryUser> findByUsername(String username);

    ExternalInvitation createInvitation(String email, Instant expiresAt);

    void deleteInvitation(String id);
}
