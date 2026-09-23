package de.raindancer118.stoneintelligence.platform.invitation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

public final class FakeInvitationRepository implements InvitationRepository {

    private final Map<UUID, Invitation> invitations = new ConcurrentHashMap<>();

    @Override
    public synchronized Invitation create(NewInvitation invitation) {
        var created = new Invitation(UUID.randomUUID(), invitation.vaultId(), invitation.email(), invitation.access(),
            invitation.tokenHash(), invitation.invitedBy(), invitation.createdAt(), invitation.expiresAt(),
            invitation.externalId(), invitation.enrollmentUrl(), null, null, null);
        invitations.put(created.id(), created);
        return created;
    }

    @Override
    public Optional<Invitation> findByTokenHash(String tokenHash) {
        return invitations.values().stream().filter(invitation -> invitation.tokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public Optional<Invitation> findById(VaultId vaultId, UUID id) {
        return Optional.ofNullable(invitations.get(id)).filter(invitation -> invitation.vaultId().equals(vaultId));
    }

    @Override
    public List<Invitation> listPending(VaultId vaultId, Instant now) {
        return invitations.values().stream()
            .filter(invitation -> invitation.vaultId().equals(vaultId) && invitation.isPending(now))
            .sorted(java.util.Comparator.comparing(Invitation::createdAt))
            .toList();
    }

    @Override
    public List<Invitation> listPendingForEmail(String email, Instant now) {
        return invitations.values().stream()
            .filter(invitation -> invitation.email().equalsIgnoreCase(email) && invitation.isPending(now))
            .toList();
    }

    @Override
    public synchronized boolean markAccepted(UUID id, String actor, Instant at) {
        var current = invitations.get(id);
        if (current == null || current.acceptedAt() != null || current.revokedAt() != null) {
            return false;
        }
        invitations.put(id, current.withAccepted(actor, at));
        return true;
    }

    @Override
    public synchronized boolean markRevoked(UUID id, Instant at) {
        var current = invitations.get(id);
        if (current == null || current.acceptedAt() != null || current.revokedAt() != null) {
            return false;
        }
        invitations.put(id, current.withRevoked(at));
        return true;
    }

    @Override
    public synchronized int purgeClosedBefore(Instant cutoff) {
        var doomed = new ArrayList<UUID>();
        invitations.values().forEach(invitation -> {
            var closedAt = invitation.acceptedAt() != null ? invitation.acceptedAt()
                : invitation.revokedAt() != null ? invitation.revokedAt() : invitation.expiresAt();
            if (closedAt.isBefore(cutoff)) {
                doomed.add(invitation.id());
            }
        });
        doomed.forEach(invitations::remove);
        return doomed.size();
    }
}
