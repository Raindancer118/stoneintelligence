package de.tstieh.stoneintelligence.platform.identity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Was jemand an einem Pfad darf (ADR 0011). Reine Funktion ohne Spring/JDBC, exhaustiv getestet.
 *
 * <ol>
 *   <li>Die spezifischste passende Freigabe gewinnt: Eintrag vor tiefstem Ordner vor Vault-Rolle.</li>
 *   <li>Auf derselben Stufe: Person vor Gruppe vor "alle"; mehrere Gruppen werden vereinigt.</li>
 *   <li>Die gewinnende Freigabe ERSETZT die Vault-Rechte - sie kann Rechte geben und nehmen.</li>
 *   <li>Nicht-Mitglieder duerfen nichts, auch wenn eine Freigabe fuer "alle" passt.</li>
 *   <li>Wer im Vault {@link Permission#MANAGE} hat, behaelt es ueberall (niemand sperrt sich aus).</li>
 * </ol>
 *
 * <p>Pfade mit abschliessendem {@code /} bezeichnen einen Ordner selbst (z. B. beim Anlegen oder
 * Loeschen eines Ordners): dann greifen die Freigaben dieses Ordners und seiner Eltern, nie eine
 * Eintrags-Freigabe.
 */
public final class AccessResolver {

    /** Rangstufe einer Eintrags-Freigabe - tiefer als jeder denkbare Ordner. */
    private static final int ENTRY_LEVEL = Integer.MAX_VALUE;

    private AccessResolver() {
    }

    public static EffectiveAccess resolve(Membership who, List<AccessGrant> grants, String path) {
        if (!who.isMember()) {
            return EffectiveAccess.none();
        }
        var isFolder = path.endsWith("/");
        var segments = segments(path);

        var bestLevel = -1;
        var bestScopeRank = -1;
        var decisive = new ArrayList<AccessGrant>();
        for (var grant : grants) {
            var level = levelOf(grant.target(), segments, isFolder);
            var scopeRank = scopeRank(grant.scope(), who);
            if (level < 0 || scopeRank < 0) {
                continue;
            }
            if (level > bestLevel || (level == bestLevel && scopeRank > bestScopeRank)) {
                bestLevel = level;
                bestScopeRank = scopeRank;
                decisive.clear();
            }
            if (level == bestLevel && scopeRank == bestScopeRank) {
                decisive.add(grant);
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

    /**
     * Ob irgendeine Freigabe an diesem Pfad greift, egal fuer wen - Grundlage des "geteilt"-
     * Kennzeichens, das nur Verwaltende sehen.
     */
    public static boolean touchedByGrant(List<AccessGrant> grants, String path) {
        var isFolder = path.endsWith("/");
        var segments = segments(path);
        return grants.stream().anyMatch(grant -> levelOf(grant.target(), segments, isFolder) >= 0);
    }

    /** {@code -1}, wenn die Freigabe fuer diesen Pfad nicht gilt; sonst ihre Spezifitaet. */
    private static int levelOf(GrantTarget target, List<String> pathSegments, boolean isFolder) {
        return switch (target) {
            case GrantTarget.Entry entry -> !isFolder && segments(entry.path()).equals(pathSegments) ? ENTRY_LEVEL : -1;
            case GrantTarget.Folder folder -> {
                var folderSegments = segments(folder.path());
                var covers = pathSegments.size() > folderSegments.size()
                    || (isFolder && pathSegments.size() == folderSegments.size());
                yield covers && pathSegments.subList(0, folderSegments.size()).equals(folderSegments)
                    ? folderSegments.size() : -1;
            }
        };
    }

    /** {@code -1}, wenn die Freigabe diese Person nicht betrifft; sonst Person 2, Gruppe 1, alle 0. */
    private static int scopeRank(GrantScope scope, Membership who) {
        return switch (scope) {
            case GrantScope.User user -> user.subject().equals(who.subject()) ? 2 : -1;
            case GrantScope.Group group -> who.groupIds().contains(group.groupId()) ? 1 : -1;
            case GrantScope.Everyone ignored -> 0;
        };
    }

    /** Pfad ohne fuehrende/abschliessende Schraegstriche, wie er in Freigaben gespeichert wird. */
    public static String normalize(String path) {
        return String.join("/", segments(path));
    }

    private static List<String> segments(String path) {
        var result = new ArrayList<String>();
        for (var segment : path.split("/")) {
            if (!segment.isEmpty()) {
                result.add(segment);
            }
        }
        return result;
    }
}
