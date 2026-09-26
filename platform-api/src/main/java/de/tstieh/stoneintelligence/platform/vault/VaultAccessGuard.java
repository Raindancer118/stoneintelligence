package de.tstieh.stoneintelligence.platform.vault;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.AccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.AccessResolver;
import de.tstieh.stoneintelligence.platform.identity.AuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.EffectiveAccess;
import de.tstieh.stoneintelligence.platform.identity.Membership;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import org.springframework.stereotype.Component;

/**
 * Die eine Stelle, an der entschieden wird, wer was darf (ADR 0006, ADR 0011): Vault-Rechte aus
 * Gruppen und Rollen, verfeinert durch Freigaben je Ordner und Eintrag ({@link AccessResolver}).
 *
 * <p>Zwei Ebenen: {@link #require(VaultId, String, Permission)} prueft ein Vault-Recht (etwa
 * {@link Permission#MANAGE} fuer die Verwaltung), die Varianten mit Pfad pruefen genau diese
 * Stelle - dort kann eine Freigabe mehr erlauben als die Vault-Rolle (eine einzelne Notiz
 * bearbeiten) oder weniger (einen Ordner ausblenden). Auflistungen verlangen deshalb nur
 * Mitgliedschaft ({@link #requireMember}) und filtern dann je Eintrag. {@code TopicRules} bleibt
 * unverdrahtet - {@link Note} traegt (noch) keine Themen.
 */
@Component
public class VaultAccessGuard {

    private final AuthorizationRepository authorization;
    private final AccessGrantRepository grants;

    public VaultAccessGuard(AuthorizationRepository authorization, AccessGrantRepository grants) {
        this.authorization = authorization;
        this.grants = grants;
    }

    public Membership membership(VaultId vaultId, String actor) {
        return authorization.membership(vaultId, actor);
    }

    /** Vault-Recht aus den Rollen, unabhaengig von Freigaben (Verwaltung, KI-Auftraege, ...). */
    public void require(VaultId vaultId, String actor, Permission permission) {
        if (!authorization.effectivePermissions(vaultId, actor).contains(permission)) {
            throw new ForbiddenException(actor + " lacks " + permission + " in vault " + vaultId.value());
        }
    }

    /** Mitglied des Vaults, egal mit welchen Rechten - Voraussetzung fuer jede Auflistung. */
    public void requireMember(VaultId vaultId, String actor) {
        if (!membership(vaultId, actor).isMember()) {
            throw new ForbiddenException(actor + " is no member of vault " + vaultId.value());
        }
    }

    /** Was {@code actor} an diesem Pfad darf; ein abschliessendes {@code /} meint den Ordner selbst. */
    public EffectiveAccess accessAt(VaultId vaultId, String actor, String path) {
        return AccessResolver.resolve(membership(vaultId, actor), grants.list(vaultId), path);
    }

    public void require(VaultId vaultId, String actor, Permission permission, String path) {
        if (!accessAt(vaultId, actor, path).allows(permission)) {
            throw new ForbiddenException(actor + " lacks " + permission + " on '" + path + "' in vault " + vaultId.value());
        }
    }

    public void requireReadablePaths(VaultId vaultId, String actor, List<String> paths) {
        if (!paths.stream().allMatch(reader(vaultId, actor))) {
            throw new ForbiddenException("audit contains a path " + actor + " may not read");
        }
    }

    public List<Note> readableNotes(VaultId vaultId, String actor, List<Note> notes) {
        var mayRead = reader(vaultId, actor);
        return notes.stream().filter(note -> mayRead.test(note.path())).toList();
    }

    /** Nur die Pfade, die {@code actor} sehen darf; {@code asRulePath} bildet z. B. Ordner auf {@code <ordner>/} ab. */
    public List<String> readablePaths(VaultId vaultId, String actor, List<String> paths, UnaryOperator<String> asRulePath) {
        var mayRead = reader(vaultId, actor);
        return paths.stream().filter(path -> mayRead.test(asRulePath.apply(path))).toList();
    }

    /**
     * Rechte je Eintrag fuer die Liste des Plugins (Schreibschutz, Kennzeichen). {@code shared}
     * sagt, ob eine Freigabe den Eintrag betrifft - nur fuer Verwaltende, sonst {@code null}.
     */
    public java.util.Map<de.tstieh.stoneintelligence.domain.id.NoteId, EntryAccess> entryAccess(VaultId vaultId, String actor, List<Note> notes) {
        var who = membership(vaultId, actor);
        var vaultGrants = grants.list(vaultId);
        var result = new java.util.HashMap<de.tstieh.stoneintelligence.domain.id.NoteId, EntryAccess>();
        for (var note : notes) {
            var effective = AccessResolver.resolve(who, vaultGrants, note.path());
            result.put(note.id(), new EntryAccess(effective.permissions(),
                effective.allows(Permission.MANAGE) ? AccessResolver.touchedByGrant(vaultGrants, note.path()) : null));
        }
        return result;
    }

    public record EntryAccess(java.util.Set<Permission> permissions, Boolean shared) {
    }

    /** Laedt Mitgliedschaft und Freigaben einmal und prueft dann beliebig viele Pfade. */
    private Predicate<String> reader(VaultId vaultId, String actor) {
        var who = membership(vaultId, actor);
        var vaultGrants = grants.list(vaultId);
        return path -> AccessResolver.resolve(who, vaultGrants, path).allows(Permission.READ);
    }
}
