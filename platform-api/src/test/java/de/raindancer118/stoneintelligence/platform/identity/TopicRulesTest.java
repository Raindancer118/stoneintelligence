package de.raindancer118.stoneintelligence.platform.identity;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Themen-/Tag-ACLs (Plan.md Abschnitt 4.4a): kein Sonderfall von {@link PathRules} (die nur
 * Ordnerpfade kennen), eigenes Regelwerk mit Praezedenz analog dazu (spezifischste Regel
 * gewinnt - hier: Nutzer-Regel schlaegt Jeder-Regel pro Thema), aber eigene Tabelle.
 *
 * <p>Policy-Entscheidung (nicht in Plan.md spezifiziert, hier getroffen): traegt eine Note
 * mehrere Themen und ergeben sich widerspruechliche Effekte, gewinnt die restriktivste - eine
 * einzelne verbotene Thema-Zuordnung darf nicht durch eine erlaubte "weggestimmt" werden.
 */
class TopicRulesTest {

    @Nested
    class NoMatchingRule {

        @Test
        void should_defaultToAllow_when_noRulesExist() {
            assertThat(TopicRules.resolve(List.of(), Set.of("finance"), "tom")).isEqualTo(RuleEffect.ALLOW);
        }

        @Test
        void should_defaultToAllow_when_noteHasNoTopics() {
            var rules = List.of(new TopicRule("finance", RuleScope.everyone(), RuleEffect.DENY));

            assertThat(TopicRules.resolve(rules, Set.of(), "tom")).isEqualTo(RuleEffect.ALLOW);
        }

        @Test
        void should_defaultToAllow_when_noteTopicsDoNotMatchAnyRule() {
            var rules = List.of(new TopicRule("finance", RuleScope.everyone(), RuleEffect.DENY));

            assertThat(TopicRules.resolve(rules, Set.of("engineering"), "tom")).isEqualTo(RuleEffect.ALLOW);
        }
    }

    @Nested
    class SingleTopic {

        @Test
        void should_applyRule_when_noteTopicMatches() {
            var rules = List.of(new TopicRule("finance", RuleScope.everyone(), RuleEffect.DENY));

            assertThat(TopicRules.resolve(rules, Set.of("finance"), "tom")).isEqualTo(RuleEffect.DENY);
        }

        @Test
        void should_notApplyRule_when_subjectDoesNotMatchUserScope() {
            var rules = List.of(new TopicRule("finance", RuleScope.user("alice"), RuleEffect.DENY));

            assertThat(TopicRules.resolve(rules, Set.of("finance"), "tom")).isEqualTo(RuleEffect.ALLOW);
        }

        @Test
        void should_preferUserRule_overEveryoneRuleForSameTopic() {
            var rules = List.of(
                new TopicRule("finance", RuleScope.everyone(), RuleEffect.DENY),
                new TopicRule("finance", RuleScope.user("tom"), RuleEffect.ALLOW)
            );

            assertThat(TopicRules.resolve(rules, Set.of("finance"), "tom")).isEqualTo(RuleEffect.ALLOW);
        }
    }

    @Nested
    class MultipleTopicsOnOneNote {

        @Test
        void should_denyOverall_when_anyMatchedTopicResolvesToDeny() {
            var rules = List.of(
                new TopicRule("finance", RuleScope.everyone(), RuleEffect.DENY),
                new TopicRule("engineering", RuleScope.everyone(), RuleEffect.ALLOW)
            );

            assertThat(TopicRules.resolve(rules, Set.of("finance", "engineering"), "tom")).isEqualTo(RuleEffect.DENY);
        }

        @Test
        void should_allow_when_allMatchedTopicsResolveToAllow() {
            var rules = List.of(
                new TopicRule("finance", RuleScope.everyone(), RuleEffect.ALLOW),
                new TopicRule("engineering", RuleScope.everyone(), RuleEffect.ALLOW)
            );

            assertThat(TopicRules.resolve(rules, Set.of("finance", "engineering"), "tom"))
                .isEqualTo(RuleEffect.ALLOW);
        }
    }

    @Nested
    class DenyWinsTiesAtEqualSpecificity {

        @Test
        void should_preferDeny_overAllowForSameTopicAndScope_regardlessOfListOrder() {
            var denyFirst = List.of(
                new TopicRule("finance", RuleScope.everyone(), RuleEffect.DENY),
                new TopicRule("finance", RuleScope.everyone(), RuleEffect.ALLOW)
            );
            var allowFirst = List.of(
                new TopicRule("finance", RuleScope.everyone(), RuleEffect.ALLOW),
                new TopicRule("finance", RuleScope.everyone(), RuleEffect.DENY)
            );

            assertThat(TopicRules.resolve(denyFirst, Set.of("finance"), "tom")).isEqualTo(RuleEffect.DENY);
            assertThat(TopicRules.resolve(allowFirst, Set.of("finance"), "tom")).isEqualTo(RuleEffect.DENY);
        }
    }
}
