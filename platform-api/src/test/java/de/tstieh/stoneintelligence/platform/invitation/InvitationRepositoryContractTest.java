package de.tstieh.stoneintelligence.platform.invitation;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gemeinsamer Vertrag fuer Fake- und Postgres-Implementierung. */
public abstract class InvitationRepositoryContractTest {

    protected abstract InvitationRepository repository();

    /** Liefert einen existierenden Vault (Postgres braucht die Fremdschluessel-Zeile). */
    protected abstract VaultId existingVault();

    private InvitationRepository invitations;
    private VaultId vaultId;
    private final Instant now = Instant.parse("2026-09-23T10:00:00Z");

    @BeforeEach
    void setUp() {
        invitations = repository();
        vaultId = existingVault();
    }

    private Invitation invite(String email, String tokenHash) {
        return invitations.create(new NewInvitation(vaultId, email, InviteAccess.EDIT, tokenHash, "tom", now,
            now.plus(Duration.ofDays(14)), "ak-1", "https://portal.example/x"));
    }

    @Test
    void should_findAnInvitation_byItsTokenHash_withAllFields() {
        var created = invite("neu@example.org", "hash-" + UUID.randomUUID());

        var found = invitations.findByTokenHash(created.tokenHash()).orElseThrow();

        assertThat(found.id()).isEqualTo(created.id());
        assertThat(found.email()).isEqualTo("neu@example.org");
        assertThat(found.access()).isEqualTo(InviteAccess.EDIT);
        assertThat(found.expiresAt().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(now.plus(Duration.ofDays(14)));
        assertThat(found.enrollmentUrl()).isEqualTo("https://portal.example/x");
        assertThat(found.state(now)).isEqualTo(InvitationState.PENDING);
    }

    @Test
    void should_letExactlyOneCallerAcceptAnInvitation() {
        var created = invite("neu@example.org", "hash-" + UUID.randomUUID());

        assertThat(invitations.markAccepted(created.id(), "neu", now)).isTrue();
        assertThat(invitations.markAccepted(created.id(), "mallory", now)).isFalse();
        assertThat(invitations.findByTokenHash(created.tokenHash()).orElseThrow().acceptedBy()).isEqualTo("neu");
    }

    @Test
    void should_listOnlyPendingInvitations_perVaultAndPerEmail() {
        var open = invite("Offen@Example.org", "hash-" + UUID.randomUUID());
        var accepted = invite("offen@example.org", "hash-" + UUID.randomUUID());
        invitations.markAccepted(accepted.id(), "x", now);
        var revoked = invite("weg@example.org", "hash-" + UUID.randomUUID());
        invitations.markRevoked(revoked.id(), now);

        assertThat(invitations.listPending(vaultId, now)).extracting(Invitation::id).containsExactly(open.id());
        assertThat(invitations.listPendingForEmail("offen@example.org", now)).extracting(Invitation::id).containsExactly(open.id());
        assertThat(invitations.listPending(vaultId, now.plus(Duration.ofDays(15)))).isEmpty();
    }

    @Test
    void should_scopeLookupById_toTheVault() {
        var created = invite("neu@example.org", "hash-" + UUID.randomUUID());

        assertThat(invitations.findById(vaultId, created.id())).isPresent();
        assertThat(invitations.findById(VaultId.newId(), created.id())).isEmpty();
    }

    @Test
    void should_purgeOnlyInvitationsClosedBeforeTheCutoff() {
        var old = invite("alt@example.org", "hash-" + UUID.randomUUID());
        invitations.markAccepted(old.id(), "alt", now);
        var fresh = invite("frisch@example.org", "hash-" + UUID.randomUUID());

        var purged = invitations.purgeClosedBefore(now.plus(Duration.ofDays(1)));

        assertThat(purged).isEqualTo(1);
        assertThat(invitations.findByTokenHash(old.tokenHash())).isEmpty();
        assertThat(invitations.findByTokenHash(fresh.tokenHash())).isPresent();
    }
}
