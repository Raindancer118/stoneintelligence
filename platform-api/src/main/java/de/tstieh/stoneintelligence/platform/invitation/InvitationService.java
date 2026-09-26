package de.tstieh.stoneintelligence.platform.invitation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.AuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.Group;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.Vault;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import de.tstieh.stoneintelligence.platform.vault.VaultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mitbearbeiter in einen Vault holen - so einfach wie moeglich:
 * <ul>
 *   <li>Wer schon ein Konto hat, wird gesucht und mit einem Klick Mitglied (Mail zur Info).</li>
 *   <li>Wer keins hat, bekommt per Mail einen Link: Konto ueber eine Authentik-Einladung anlegen,
 *   anmelden, Einladung annehmen. Bestaetigt der Identity-Provider die E-Mail-Adresse
 *   ({@code email_verified}), geschieht das Annehmen beim Anmelden automatisch.</li>
 * </ul>
 * Alle verwaltenden Aktionen verlangen {@link Permission#MANAGE}.
 */
public class InvitationService {

    private static final Logger LOG = LoggerFactory.getLogger(InvitationService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int SEARCH_LIMIT = 10;
    private static final Duration RETENTION_AFTER_CLOSE = Duration.ofDays(30);

    private final InvitationRepository invitations;
    private final UserDirectory directory;
    private final Mailer mailer;
    private final AuthorizationRepository authorization;
    private final VaultAccessGuard access;
    private final VaultRepository vaults;
    private final Clock clock;
    private final InvitationSettings settings;
    private final SecureRandom random = new SecureRandom();

    public InvitationService(InvitationRepository invitations, UserDirectory directory, Mailer mailer,
                             AuthorizationRepository authorization, VaultAccessGuard access, VaultRepository vaults,
                             Clock clock, InvitationSettings settings) {
        this.invitations = invitations;
        this.directory = directory;
        this.mailer = mailer;
        this.authorization = authorization;
        this.access = access;
        this.vaults = vaults;
        this.clock = clock;
        this.settings = settings;
    }

    public List<PersonSuggestion> searchPeople(VaultId vaultId, String actor, String query) {
        access.require(vaultId, actor, Permission.MANAGE);
        var needle = query == null ? "" : query.strip();
        if (needle.length() < 2 || !directory.isAvailable()) {
            return List.of();
        }
        var members = members(vaultId);
        return directory.search(needle, SEARCH_LIMIT).stream()
            .map(user -> new PersonSuggestion(user.username(), user.name(), maskEmail(user.email()), members.contains(user.username())))
            .toList();
    }

    public InviteResult addExisting(VaultId vaultId, String actor, String username, InviteAccess level) {
        access.require(vaultId, actor, Permission.MANAGE);
        var user = directory.findByUsername(username)
            .orElseThrow(() -> new InvitationException("Dieses Konto gibt es nicht."));
        return addMember(vaultId, actor, user, level);
    }

    public InviteResult inviteByEmail(VaultId vaultId, String actor, String rawEmail, InviteAccess level) {
        access.require(vaultId, actor, Permission.MANAGE);
        var email = normalizeEmail(rawEmail);
        var existing = directory.isAvailable() ? directory.findByEmail(email) : java.util.Optional.<DirectoryUser>empty();
        if (existing.isPresent()) {
            return addMember(vaultId, actor, existing.get(), level);
        }

        var vault = vault(vaultId);
        var now = clock.instant();
        var expiresAt = now.plus(settings.validity());
        var token = newToken();
        ExternalInvitation external = null;
        if (directory.isAvailable()) {
            external = directory.createInvitation(email, expiresAt);
        }
        var created = invitations.create(new NewInvitation(vaultId, email, level, hash(token), actor, now, expiresAt,
            external == null ? null : external.id(), external == null ? null : external.enrollmentUrl()));
        try {
            mailer.send(InvitationMails.invitation(email, vault.name(), actor, level, inviteUrl(token), expiresAt));
        } catch (MailDeliveryException undelivered) {
            // Eine Einladung, deren Link niemand bekommen hat, wuerde nur als "offen" herumstehen.
            invitations.markRevoked(created.id(), clock.instant());
            deleteExternal(created);
            LOG.warn("Einladungsmail an {} nicht zustellbar", email, undelivered);
            throw new InvitationException("Die E-Mail konnte nicht zugestellt werden. Bitte später erneut versuchen.");
        }
        return new InviteResult(InviteStatus.INVITED, email);
    }

    public List<PendingInvitation> listPending(VaultId vaultId, String actor) {
        access.require(vaultId, actor, Permission.MANAGE);
        return invitations.listPending(vaultId, clock.instant()).stream()
            .map(invitation -> new PendingInvitation(invitation.id(), invitation.email(), invitation.access(),
                invitation.invitedBy(), invitation.createdAt(), invitation.expiresAt()))
            .toList();
    }

    public void revoke(VaultId vaultId, String actor, UUID invitationId) {
        access.require(vaultId, actor, Permission.MANAGE);
        var invitation = invitations.findById(vaultId, invitationId)
            .orElseThrow(() -> new InvitationException("Diese Einladung gibt es nicht."));
        if (invitations.markRevoked(invitation.id(), clock.instant())) {
            deleteExternal(invitation);
        }
    }

    public InvitationInfo describe(String token) {
        var invitation = byToken(token);
        var vaultName = vaults.findById(invitation.vaultId()).map(Vault::name).orElse("Gelöschter Vault");
        return new InvitationInfo(invitation.state(clock.instant()), vaultName, invitation.invitedBy(),
            maskEmail(invitation.email()), invitation.access(), invitation.expiresAt(), invitation.enrollmentUrl());
    }

    public JoinedVault accept(String token, String actor) {
        var invitation = byToken(token);
        var state = invitation.state(clock.instant());
        if (state != InvitationState.PENDING) {
            throw new InvitationException(switch (state) {
                case ACCEPTED -> "Diese Einladung wurde bereits angenommen.";
                case REVOKED -> "Diese Einladung wurde zurückgezogen.";
                default -> "Diese Einladung ist abgelaufen. Bitte lass dich erneut einladen.";
            });
        }
        return join(invitation, actor);
    }

    /**
     * Beim Anmelden: offene Einladungen an genau diese (vom Identity-Provider gelieferte) Adresse
     * werden automatisch angenommen - wer sich ueber die Einladung registriert hat, muss den Link
     * danach nicht noch einmal oeffnen.
     */
    public List<VaultId> acceptPendingForEmail(String actor, String email) {
        if (email == null || email.isBlank()) {
            return List.of();
        }
        var joined = new ArrayList<VaultId>();
        for (var invitation : invitations.listPendingForEmail(email.strip().toLowerCase(Locale.ROOT), clock.instant())) {
            try {
                join(invitation, actor);
                joined.add(invitation.vaultId());
            } catch (InvitationException raceLost) {
                // Zeitgleich anderweitig eingeloest - nichts mehr zu tun.
            }
        }
        return joined;
    }

    public int purgeClosed() {
        return invitations.purgeClosedBefore(clock.instant().minus(RETENTION_AFTER_CLOSE));
    }

    private JoinedVault join(Invitation invitation, String actor) {
        if (!invitations.markAccepted(invitation.id(), actor, clock.instant())) {
            throw new InvitationException("Diese Einladung wurde bereits verwendet.");
        }
        var group = ensureGroup(invitation.vaultId(), invitation.access());
        authorization.addMember(group.id(), actor);
        deleteExternal(invitation);
        var vault = vault(invitation.vaultId());
        return new JoinedVault(vault.id().value().toString(), vault.name());
    }

    private InviteResult addMember(VaultId vaultId, String actor, DirectoryUser user, InviteAccess level) {
        if (members(vaultId).contains(user.username())) {
            return new InviteResult(InviteStatus.ALREADY_MEMBER, user.name());
        }
        var group = ensureGroup(vaultId, level);
        authorization.addMember(group.id(), user.username());
        if (user.email() != null && !user.email().isBlank()) {
            try {
                mailer.send(InvitationMails.added(user.email(), user.name(), vault(vaultId).name(), actor, level, settings.webappUrl()));
            } catch (MailDeliveryException undelivered) {
                // Nur eine Info - die Mitgliedschaft selbst gilt trotzdem.
                LOG.warn("Hinweismail an {} nicht zustellbar", user.email(), undelivered);
            }
        }
        return new InviteResult(InviteStatus.ADDED, user.name());
    }

    /** Die Gruppe zur Stufe ("Mitbearbeiter"/"Leser") gibt es je Vault genau einmal - bei Bedarf samt Rolle anlegen. */
    private Group ensureGroup(VaultId vaultId, InviteAccess level) {
        var existing = authorization.listGroups(vaultId).stream()
            .filter(group -> group.name().equals(level.groupName())).findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        var role = authorization.listRoles(vaultId).stream()
            .filter(candidate -> candidate.name().equals(level.groupName())).findFirst()
            .orElseGet(() -> authorization.createRole(vaultId, level.groupName(), level.permissions()));
        var group = authorization.createGroup(vaultId, level.groupName());
        authorization.assignRole(group.id(), role.id());
        return group;
    }

    private Set<String> members(VaultId vaultId) {
        return authorization.listGroups(vaultId).stream()
            .flatMap(group -> group.memberSubjects().stream())
            .collect(Collectors.toSet());
    }

    private Vault vault(VaultId vaultId) {
        return vaults.findById(vaultId).orElseThrow(() -> new InvitationException("Diesen Vault gibt es nicht mehr."));
    }

    private Invitation byToken(String token) {
        if (token == null || token.length() < 20) {
            throw new InvitationException("Dieser Einladungslink ist ungültig.");
        }
        return invitations.findByTokenHash(hash(token))
            .orElseThrow(() -> new InvitationException("Dieser Einladungslink ist ungültig."));
    }

    private void deleteExternal(Invitation invitation) {
        if (invitation.externalId() == null || !directory.isAvailable()) {
            return;
        }
        try {
            directory.deleteInvitation(invitation.externalId());
        } catch (RuntimeException alreadyGoneOrUnreachable) {
            // Authentik-Einladungen laufen ohnehin ab und sind einmalig - kein Grund, die Annahme scheitern zu lassen.
            LOG.warn("Authentik-Einladung {} konnte nicht entfernt werden", invitation.externalId(), alreadyGoneOrUnreachable);
        }
    }

    private String inviteUrl(String token) {
        return settings.webappUrl().replaceAll("/+$", "") + "/invite/" + token;
    }

    private String newToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String normalizeEmail(String raw) {
        var email = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new InvitationException("Bitte eine gültige E-Mail-Adresse angeben.");
        }
        return email;
    }

    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "";
        }
        var at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }
}
