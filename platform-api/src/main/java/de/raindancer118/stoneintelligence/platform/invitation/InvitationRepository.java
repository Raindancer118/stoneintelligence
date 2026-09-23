package de.raindancer118.stoneintelligence.platform.invitation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

public interface InvitationRepository {

    Invitation create(NewInvitation invitation);

    Optional<Invitation> findByTokenHash(String tokenHash);

    Optional<Invitation> findById(VaultId vaultId, UUID id);

    List<Invitation> listPending(VaultId vaultId, Instant now);

    List<Invitation> listPendingForEmail(String email, Instant now);

    /** Atomar: nur EIN Aufrufer kann eine offene Einladung einloesen. */
    boolean markAccepted(UUID id, String actor, Instant at);

    boolean markRevoked(UUID id, Instant at);

    /** Datensparsamkeit: angenommene, widerrufene und abgelaufene Einladungen verschwinden nach der Frist. */
    int purgeClosedBefore(Instant cutoff);
}
