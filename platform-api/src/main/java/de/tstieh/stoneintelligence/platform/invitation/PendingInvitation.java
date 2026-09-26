package de.tstieh.stoneintelligence.platform.invitation;

import java.time.Instant;
import java.util.UUID;

public record PendingInvitation(UUID id, String email, InviteAccess access, String invitedBy, Instant createdAt, Instant expiresAt) {
}
