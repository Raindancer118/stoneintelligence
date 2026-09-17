package de.raindancer118.stoneintelligence.platform.identity;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PathRules-Praezedenzlogik (Plan.md Abschnitt 3, positiv uebernommenes Muster aus stonesync):
 * laengster Pfad-Praefix gewinnt, Nutzer-Regel schlaegt Jeder-Regel, reine Funktion ohne
 * Spring/JPA-Abhaengigkeit, exhaustiv testbar.
 */
class PathRulesTest {

    @Nested
    class NoMatchingRule {

        @Test
        void should_defaultToAllow_when_noRulesExist() {
            assertThat(PathRules.resolve(List.of(), "foo/bar.md", "tom")).isEqualTo(RuleEffect.ALLOW);
        }

        @Test
        void should_notMatchPartialSegment_when_prefixIsNotAFullPathSegment() {
            // "foo" darf NICHT "foobar/x.md" treffen - reiner String-Praefixvergleich waere ein Bug.
            var rules = List.of(new PathRule("foo", RuleScope.everyone(), RuleEffect.DENY));

            assertThat(PathRules.resolve(rules, "foobar/x.md", "tom")).isEqualTo(RuleEffect.ALLOW);
        }
    }

    @Nested
    class SingleRule {

        @Test
        void should_applyRule_when_pathIsUnderPrefix() {
            var rules = List.of(new PathRule("private", RuleScope.everyone(), RuleEffect.DENY));

            assertThat(PathRules.resolve(rules, "private/secret.md", "tom")).isEqualTo(RuleEffect.DENY);
        }

        @Test
        void should_applyRule_when_pathEqualsPrefixExactly() {
            var rules = List.of(new PathRule("private/secret.md", RuleScope.everyone(), RuleEffect.DENY));

            assertThat(PathRules.resolve(rules, "private/secret.md", "tom")).isEqualTo(RuleEffect.DENY);
        }

        @Test
        void should_notApplyRule_when_subjectDoesNotMatchUserScope() {
            var rules = List.of(new PathRule("private", RuleScope.user("alice"), RuleEffect.DENY));

            assertThat(PathRules.resolve(rules, "private/secret.md", "tom")).isEqualTo(RuleEffect.ALLOW);
        }

        @Test
        void should_applyRule_when_subjectMatchesUserScope() {
            var rules = List.of(new PathRule("private", RuleScope.user("tom"), RuleEffect.DENY));

            assertThat(PathRules.resolve(rules, "private/secret.md", "tom")).isEqualTo(RuleEffect.DENY);
        }
    }

    @Nested
    class LongestPrefixWins {

        @Test
        void should_preferMoreSpecificAllow_overLessSpecificDeny() {
            var rules = List.of(
                new PathRule("private", RuleScope.everyone(), RuleEffect.DENY),
                new PathRule("private/shared", RuleScope.everyone(), RuleEffect.ALLOW)
            );

            assertThat(PathRules.resolve(rules, "private/shared/note.md", "tom")).isEqualTo(RuleEffect.ALLOW);
        }

        @Test
        void should_preferMoreSpecificDeny_overLessSpecificAllow() {
            var rules = List.of(
                new PathRule("team", RuleScope.everyone(), RuleEffect.ALLOW),
                new PathRule("team/finance", RuleScope.everyone(), RuleEffect.DENY)
            );

            assertThat(PathRules.resolve(rules, "team/finance/q3.md", "tom")).isEqualTo(RuleEffect.DENY);
        }
    }

    @Nested
    class UserRuleBeatsEveryoneRule {

        @Test
        void should_preferUserRule_overEveryoneRuleAtSamePrefix_regardlessOfListOrder() {
            var everyoneFirst = List.of(
                new PathRule("private", RuleScope.everyone(), RuleEffect.DENY),
                new PathRule("private", RuleScope.user("tom"), RuleEffect.ALLOW)
            );
            var userFirst = List.of(
                new PathRule("private", RuleScope.user("tom"), RuleEffect.ALLOW),
                new PathRule("private", RuleScope.everyone(), RuleEffect.DENY)
            );

            assertThat(PathRules.resolve(everyoneFirst, "private/note.md", "tom")).isEqualTo(RuleEffect.ALLOW);
            assertThat(PathRules.resolve(userFirst, "private/note.md", "tom")).isEqualTo(RuleEffect.ALLOW);
        }

        @Test
        void should_stillApplyEveryoneRule_forADifferentSubjectThanTheUserRule() {
            var rules = List.of(
                new PathRule("private", RuleScope.everyone(), RuleEffect.DENY),
                new PathRule("private", RuleScope.user("tom"), RuleEffect.ALLOW)
            );

            assertThat(PathRules.resolve(rules, "private/note.md", "alice")).isEqualTo(RuleEffect.DENY);
        }
    }
}
