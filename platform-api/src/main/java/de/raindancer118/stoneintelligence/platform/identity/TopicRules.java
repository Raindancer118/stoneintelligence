package de.raindancer118.stoneintelligence.platform.identity;

import java.util.List;
import java.util.Set;

/**
 * Praezedenzlogik fuer themen-/tag-basierte Sichteinschraenkungen (Plan.md Abschnitt 4.4a):
 * pro Thema gewinnt eine Nutzer-Regel gegen eine Jeder-Regel (analog {@link PathRules}). Eine
 * Note kann mehrere Themen tragen - traegt eine Note mehrere und ergeben diese widerspruechliche
 * Effekte, gewinnt die restriktivste Einzelentscheidung (eine verbotene Themen-Zuordnung darf
 * nicht durch eine erlaubte "weggestimmt" werden). Diese Kombinationsregel ist eine bewusste
 * Policy-Entscheidung, da Plan.md sie nicht spezifiziert - zur Bestaetigung markiert.
 */
public final class TopicRules {

    private TopicRules() {
    }

    public static RuleEffect resolve(List<TopicRule> rules, Set<String> noteTopics, String subject) {
        for (var topic : noteTopics) {
            if (resolveSingleTopic(rules, topic, subject).filter(effect -> effect == RuleEffect.DENY).isPresent()) {
                return RuleEffect.DENY;
            }
        }
        return RuleEffect.ALLOW;
    }

    private static java.util.Optional<RuleEffect> resolveSingleTopic(List<TopicRule> rules, String topic, String subject) {
        TopicRule best = null;
        for (var rule : rules) {
            if (!rule.topic().equals(topic)) {
                continue;
            }
            if (!appliesTo(rule.scope(), subject)) {
                continue;
            }
            if (best == null || isMoreSpecific(rule, best)) {
                best = rule;
            }
        }
        return best == null ? java.util.Optional.empty() : java.util.Optional.of(best.effect());
    }

    private static boolean appliesTo(RuleScope scope, String subject) {
        return switch (scope) {
            case RuleScope.Everyone ignored -> true;
            case RuleScope.User user -> user.subject().equals(subject);
        };
    }

    /**
     * Deterministisch bis zum Schluss: bei gleicher Scope-Spezifitaet fuer dasselbe Thema (z. B.
     * zwei "Jeder"-Regeln mit widerspruechlichem Effekt) gewinnt DENY - sicherer Default statt
     * von der (nicht garantierten) DB-Rueckgabereihenfolge abzuhaengen.
     */
    private static boolean isMoreSpecific(TopicRule candidate, TopicRule current) {
        var candidateIsUserRule = candidate.scope() instanceof RuleScope.User;
        var currentIsUserRule = current.scope() instanceof RuleScope.User;
        if (candidateIsUserRule != currentIsUserRule) {
            return candidateIsUserRule;
        }
        return candidate.effect() == RuleEffect.DENY && current.effect() != RuleEffect.DENY;
    }
}
