package de.tstieh.stoneintelligence.platform.invitation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcInvitationRepository implements InvitationRepository {

    private static final String PENDING = "accepted_at IS NULL AND revoked_at IS NULL AND expires_at > :now";

    private final JdbcClient jdbcClient;

    public JdbcInvitationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Invitation map(ResultSet rs, int rowNum) throws SQLException {
        return new Invitation(UUID.fromString(rs.getString("id")), VaultId.of(rs.getString("vault_id")), rs.getString("email"),
            InviteAccess.valueOf(rs.getString("access")), rs.getString("token_hash"), rs.getString("invited_by"),
            instant(rs, "created_at"), instant(rs, "expires_at"), rs.getString("external_id"), rs.getString("enrollment_url"),
            rs.getString("accepted_by"), instant(rs, "accepted_at"), instant(rs, "revoked_at"));
    }

    @Override
    public Invitation create(NewInvitation invitation) {
        return jdbcClient.sql("""
                INSERT INTO platform.vault_invitations
                    (vault_id, email, access, token_hash, invited_by, created_at, expires_at, external_id, enrollment_url)
                VALUES (:vaultId, :email, :access, :tokenHash, :invitedBy, :createdAt, :expiresAt, :externalId, :enrollmentUrl)
                RETURNING *
                """)
            .param("vaultId", invitation.vaultId().value())
            .param("email", invitation.email())
            .param("access", invitation.access().name())
            .param("tokenHash", invitation.tokenHash())
            .param("invitedBy", invitation.invitedBy())
            .param("createdAt", Timestamp.from(invitation.createdAt()))
            .param("expiresAt", Timestamp.from(invitation.expiresAt()))
            .param("externalId", invitation.externalId())
            .param("enrollmentUrl", invitation.enrollmentUrl())
            .query(JdbcInvitationRepository::map)
            .single();
    }

    @Override
    public Optional<Invitation> findByTokenHash(String tokenHash) {
        return jdbcClient.sql("SELECT * FROM platform.vault_invitations WHERE token_hash = :hash")
            .param("hash", tokenHash).query(JdbcInvitationRepository::map).optional();
    }

    @Override
    public Optional<Invitation> findById(VaultId vaultId, UUID id) {
        return jdbcClient.sql("SELECT * FROM platform.vault_invitations WHERE id = :id AND vault_id = :vaultId")
            .param("id", id).param("vaultId", vaultId.value()).query(JdbcInvitationRepository::map).optional();
    }

    @Override
    public List<Invitation> listPending(VaultId vaultId, Instant now) {
        return jdbcClient.sql("SELECT * FROM platform.vault_invitations WHERE vault_id = :vaultId AND " + PENDING + " ORDER BY created_at")
            .param("vaultId", vaultId.value()).param("now", Timestamp.from(now)).query(JdbcInvitationRepository::map).list();
    }

    @Override
    public List<Invitation> listPendingForEmail(String email, Instant now) {
        return jdbcClient.sql("SELECT * FROM platform.vault_invitations WHERE lower(email) = lower(:email) AND " + PENDING)
            .param("email", email).param("now", Timestamp.from(now)).query(JdbcInvitationRepository::map).list();
    }

    @Override
    public boolean markAccepted(UUID id, String actor, Instant at) {
        return jdbcClient.sql("""
                UPDATE platform.vault_invitations SET accepted_by = :actor, accepted_at = :at
                WHERE id = :id AND accepted_at IS NULL AND revoked_at IS NULL
                """)
            .param("actor", actor).param("at", Timestamp.from(at)).param("id", id).update() == 1;
    }

    @Override
    public boolean markRevoked(UUID id, Instant at) {
        return jdbcClient.sql("""
                UPDATE platform.vault_invitations SET revoked_at = :at
                WHERE id = :id AND accepted_at IS NULL AND revoked_at IS NULL
                """)
            .param("at", Timestamp.from(at)).param("id", id).update() == 1;
    }

    @Override
    public int purgeClosedBefore(Instant cutoff) {
        return jdbcClient.sql("""
                DELETE FROM platform.vault_invitations
                WHERE COALESCE(accepted_at, revoked_at, expires_at) < :cutoff
                """)
            .param("cutoff", Timestamp.from(cutoff)).update();
    }
}
