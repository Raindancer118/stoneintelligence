package de.raindancer118.stoneintelligence.platform.invitation;

import java.time.Instant;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

public record NewInvitation(VaultId vaultId, String email, InviteAccess access, String tokenHash, String invitedBy,
                            Instant createdAt, Instant expiresAt, String externalId, String enrollmentUrl) {
}
