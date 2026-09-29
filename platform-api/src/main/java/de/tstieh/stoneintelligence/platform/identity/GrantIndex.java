package de.tstieh.stoneintelligence.platform.identity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Freigaben eines Vaults nach Pfad geordnet: {@link #resolve} sieht nur die Freigaben an den
 * Vorfahren eines Pfads an statt aller (ADR 0013). Entscheidet exakt wie
 * {@link AccessResolver#resolve}; {@code GrantIndexTest} vergleicht beide an Zufallsfaellen.
 */
public final class GrantIndex {

    private final Map<String, List<AccessGrant>> folders = new HashMap<>();
    private final Map<String, List<AccessGrant>> entries = new HashMap<>();

    private GrantIndex(List<AccessGrant> grants) {
        for (var grant : grants) {
            switch (grant.target()) {
                case GrantTarget.Folder folder -> folders.computeIfAbsent(folder.path(), key -> new ArrayList<>()).add(grant);
                case GrantTarget.Entry entry -> entries.computeIfAbsent(AccessResolver.normalize(entry.path()), key -> new ArrayList<>()).add(grant);
            }
        }
    }

    public static GrantIndex of(List<AccessGrant> grants) {
        return new GrantIndex(grants);
    }

    public EffectiveAccess resolve(Membership who, String path) {
        if (!who.isMember()) {
            return EffectiveAccess.none();
        }
        var decisive = List.<AccessGrant>of();
        for (var candidates : candidates(path)) {
            decisive = strongest(candidates, who);
            if (!decisive.isEmpty()) {
                break;
            }
        }
        var permissions = EnumSet.noneOf(Permission.class);
        if (decisive.isEmpty()) {
            permissions.addAll(who.vaultPermissions());
        }
        for (var grant : decisive) {
            permissions.addAll(grant.inheritsVault() ? who.vaultPermissions() : grant.permissions());
        }
        if (who.vaultPermissions().contains(Permission.MANAGE)) {
            permissions.add(Permission.MANAGE);
        }
        return new EffectiveAccess(permissions, decisive.isEmpty() ? null : decisive.getFirst());
    }

    /** Wie {@link AccessResolver#touchedByGrant}. */
    public boolean touched(String path) {
        return !candidates(path).isEmpty();
    }

    /** Freigaben, die an diesem Pfad gelten koennen, spezifischste Stufe zuerst. */
    private List<List<AccessGrant>> candidates(String path) {
        var isFolder = path.endsWith("/");
        var segments = AccessResolver.normalize(path);
        var parts = segments.isEmpty() ? new String[0] : segments.split("/");
        var result = new ArrayList<List<AccessGrant>>();
        if (!isFolder) {
            var entry = entries.get(segments);
            if (entry != null) {
                result.add(entry);
            }
        }
        for (var depth = isFolder ? parts.length : parts.length - 1; depth >= 0; depth--) {
            var folder = folders.get(String.join("/", java.util.Arrays.asList(parts).subList(0, depth)));
            if (folder != null) {
                result.add(folder);
            }
        }
        return result;
    }

    /** Die Freigaben mit dem hoechsten Rang (Person > Gruppe > alle), die diese Person betreffen. */
    private static List<AccessGrant> strongest(List<AccessGrant> grants, Membership who) {
        var best = -1;
        var result = new ArrayList<AccessGrant>();
        for (var grant : grants) {
            var rank = switch (grant.scope()) {
                case GrantScope.User user -> user.subject().equals(who.subject()) ? 2 : -1;
                case GrantScope.Group group -> who.groupIds().contains(group.groupId()) ? 1 : -1;
                case GrantScope.Everyone ignored -> 0;
            };
            if (rank > best) {
                best = rank;
                result.clear();
            }
            if (rank >= 0 && rank == best) {
                result.add(grant);
            }
        }
        return result;
    }
}
