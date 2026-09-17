package de.raindancer118.stoneintelligence.platform.identity;

import java.util.Arrays;
import java.util.List;

/**
 * Praezedenzlogik fuer ordnerbasierte Sichteinschraenkungen (Plan.md Abschnitt 3, positiv
 * uebernommenes Muster aus stonesync): laengster Pfad-Praefix gewinnt, bei gleicher Praefix-
 * Laenge schlaegt eine Nutzer-Regel eine Jeder-Regel. Reine Funktion ohne Spring/JPA-
 * Abhaengigkeit, exhaustiv testbar. Deckt NUR Ordnerpfade ab - Themen-/Tag-basierte
 * Einschraenkungen leben in einem eigenen Regelwerk (s. Plan.md Abschnitt 4.4a).
 */
public final class PathRules {

    private PathRules() {
    }

    public static RuleEffect resolve(List<PathRule> rules, String path, String subject) {
        var pathSegments = segments(path);

        PathRule best = null;
        for (var rule : rules) {
            if (!appliesTo(rule.scope(), subject)) {
                continue;
            }
            if (!matchesPath(segments(rule.pathPrefix()), pathSegments)) {
                continue;
            }
            if (best == null || isMoreSpecific(rule, best)) {
                best = rule;
            }
        }

        return best == null ? RuleEffect.ALLOW : best.effect();
    }

    private static boolean appliesTo(RuleScope scope, String subject) {
        return switch (scope) {
            case RuleScope.Everyone ignored -> true;
            case RuleScope.User user -> user.subject().equals(subject);
        };
    }

    private static boolean isMoreSpecific(PathRule candidate, PathRule current) {
        var candidateLength = segments(candidate.pathPrefix()).size();
        var currentLength = segments(current.pathPrefix()).size();
        if (candidateLength != currentLength) {
            return candidateLength > currentLength;
        }
        var candidateIsUserRule = candidate.scope() instanceof RuleScope.User;
        var currentIsUserRule = current.scope() instanceof RuleScope.User;
        return candidateIsUserRule && !currentIsUserRule;
    }

    private static boolean matchesPath(List<String> prefixSegments, List<String> pathSegments) {
        if (prefixSegments.size() > pathSegments.size()) {
            return false;
        }
        for (int i = 0; i < prefixSegments.size(); i++) {
            if (!prefixSegments.get(i).equals(pathSegments.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> segments(String path) {
        return Arrays.stream(path.split("/")).filter(segment -> !segment.isBlank()).toList();
    }
}
