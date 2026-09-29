package de.tstieh.stoneintelligence.platform.vault;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.AccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.AccessResolver;
import de.tstieh.stoneintelligence.platform.identity.AccessVersions;
import de.tstieh.stoneintelligence.platform.identity.AuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.EffectiveAccess;
import de.tstieh.stoneintelligence.platform.identity.GrantIndex;
import de.tstieh.stoneintelligence.platform.identity.Membership;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 *
 * <p>ADR 0013: Freigaben ({@link GrantIndex}) und Mitgliedschaften liegen je Vault im Speicher,
 * solange sich die {@link AccessVersions Zugriffs-Version} nicht aendert. Die Version wird hoechstens
 * einmal je {@link #VERSION_CHECK_NANOS} gelesen - Aenderungen ueber eine andere Instanz wirken also
 * spaetestens nach dieser Zeit, Aenderungen ueber diese sofort ({@link #accessChanged}).
 */
@Component
public class VaultAccessGuard {

    static final long VERSION_CHECK_NANOS = 1_000_000_000L;

    private final AuthorizationRepository authorization;
    private final AccessGrantRepository grants;
    private final AccessVersions versions;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<VaultId, VaultState> cache = new ConcurrentHashMap<>();

    /** Ohne Cache - jede Pruefung liest frisch (Tests der Rechte-Logik selbst). */
    public VaultAccessGuard(AuthorizationRepository authorization, AccessGrantRepository grants) {
        this(authorization, grants, null, System::nanoTime);
    }

    VaultAccessGuard(AuthorizationRepository authorization, AccessGrantRepository grants, AccessVersions versions,
                     LongSupplier nanoClock) {
        this.authorization = authorization;
        this.grants = grants;
        this.versions = versions;
        this.nanoClock = nanoClock;
    }

    @Autowired
    public VaultAccessGuard(AuthorizationRepository authorization, AccessGrantRepository grants, AccessVersions versions,
                            VaultAnnouncementService announcements) {
        this(authorization, grants, versions, System::nanoTime);
        announcements.addAccessListener(this::forget);
    }

    /** Freigaben oder Mitgliedschaften dieses Vaults haben sich geaendert - nach dem Commit neu laden. */
    public void accessChanged(VaultId vaultId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    forget(vaultId);
                }
            });
        } else {
            forget(vaultId);
        }
    }

    private void forget(VaultId vaultId) {
        cache.remove(vaultId);
    }

    public Membership membership(VaultId vaultId, String actor) {
        var state = state(vaultId);
        return state == null ? authorization.membership(vaultId, actor)
            : state.members().computeIfAbsent(actor, subject -> authorization.membership(vaultId, subject));
    }

    /** Vault-Recht aus den Rollen, unabhaengig von Freigaben (Verwaltung, KI-Auftraege, ...). */
    public void require(VaultId vaultId, String actor, Permission permission) {
        if (!membership(vaultId, actor).vaultPermissions().contains(permission)) {
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
        return index(vaultId).resolve(membership(vaultId, actor), path);
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
        var index = index(vaultId);
        var result = new java.util.HashMap<de.tstieh.stoneintelligence.domain.id.NoteId, EntryAccess>();
        for (var note : notes) {
            var effective = index.resolve(who, note.path());
            result.put(note.id(), new EntryAccess(effective.permissions(),
                effective.allows(Permission.MANAGE) ? index.touched(note.path()) : null));
        }
        return result;
    }

    public record EntryAccess(java.util.Set<Permission> permissions, Boolean shared) {
    }

    /** Wie {@link #accessAt}, fuer beliebig viele Pfade derselben Person. */
    public java.util.function.Function<String, EffectiveAccess> accessChecker(VaultId vaultId, String actor) {
        var who = membership(vaultId, actor);
        var index = index(vaultId);
        return path -> index.resolve(who, path);
    }

    private Predicate<String> reader(VaultId vaultId, String actor) {
        var check = accessChecker(vaultId, actor);
        return path -> check.apply(path).allows(Permission.READ);
    }

    private GrantIndex index(VaultId vaultId) {
        var state = state(vaultId);
        return state == null ? GrantIndex.of(grants.list(vaultId)) : state.index();
    }

    /** {@code null} ohne Cache; sonst der Stand dieses Vaults, bei Bedarf neu geladen. */
    private VaultState state(VaultId vaultId) {
        if (versions == null) {
            return null;
        }
        var now = nanoClock.getAsLong();
        var cached = cache.get(vaultId);
        if (cached != null && now - cached.checkedAt() < VERSION_CHECK_NANOS) {
            return cached;
        }
        var version = versions.current(vaultId);
        if (cached != null && cached.version() == version) {
            var refreshed = cached.checked(now);
            cache.replace(vaultId, cached, refreshed);
            return refreshed;
        }
        var fresh = new VaultState(version, now, GrantIndex.of(grants.list(vaultId)), new ConcurrentHashMap<>());
        cache.put(vaultId, fresh);
        return fresh;
    }

    /** Mitgliedschaften wachsen hoechstens bis zur Zahl der Personen, die in dieser Version anfragen. */
    private record VaultState(long version, long checkedAt, GrantIndex index, ConcurrentHashMap<String, Membership> members) {
        VaultState checked(long at) {
            return new VaultState(version, at, index, members);
        }
    }
}
