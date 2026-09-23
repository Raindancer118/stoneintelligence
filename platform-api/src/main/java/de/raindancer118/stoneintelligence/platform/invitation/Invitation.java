package de.raindancer118.stoneintelligence.platform.invitation;

import java.time.Instant;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/** Gespeichert wird nur der Hash des Tokens - wer die Datenbank liest, kann damit keine Einladung einloesen. */
public record Invitation(UUID id, VaultId vaultId, String email, InviteAccess access, String tokenHash, String invitedBy,
                         Instant createdAt, Instant expiresAt, String externalId, String enrollmentUrl,
                         String acceptedBy, Instant acceptedAt, Instant revokedAt) {

    public InvitationState state(Instant now) {
        if (acceptedAt != null) {
            return InvitationState.ACCEPTED;
        }
        if (revokedAt != null) {
            return InvitationState.REVOKED;
        }
        return now.isBefore(expiresAt) ? InvitationState.PENDING : InvitationState.EXPIRED;
    }

    public boolean isPending(Instant now) {
        return state(now) == InvitationState.PENDING;
    }

    Invitation withAccepted(String actor, Instant at) {
        return new Invitation(id, vaultId, email, access, tokenHash, invitedBy, createdAt, expiresAt, externalId,
            enrollmentUrl, actor, at, revokedAt);
    }

    Invitation withRevoked(Instant at) {
        return new Invitation(id, vaultId, email, access, tokenHash, invitedBy, createdAt, expiresAt, externalId,
            enrollmentUrl, acceptedBy, acceptedAt, at);
    }
}
