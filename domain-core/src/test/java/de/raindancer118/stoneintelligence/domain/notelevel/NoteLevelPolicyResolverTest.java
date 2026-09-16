package de.raindancer118.stoneintelligence.domain.notelevel;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoteLevelPolicyResolverTest {

    private final NoteLevelPolicyResolver resolver = NoteLevelPolicyResolver.withDefaults();

    @Nested
    class Level1 {

        @Test
        void should_beOpenAndServerReadableAndAgentProcessable_when_resolvingLevel1() {
            var profile = resolver.resolve(NoteLevel.of(1));

            assertThat(profile.sync()).isEqualTo(SyncMode.ALLOWED);
            assertThat(profile.serverReadable()).isTrue();
            assertThat(profile.agentProcessing()).isEqualTo(AgentProcessing.ALLOWED);
            assertThat(profile.sharing()).isEqualTo(Sharing.OPEN);
        }
    }

    @Nested
    class Level100 {

        @Test
        void should_denySyncAndAgentProcessing_when_resolvingLevel100() {
            var profile = resolver.resolve(NoteLevel.of(100));

            assertThat(profile.sync()).isEqualTo(SyncMode.DENIED);
            assertThat(profile.serverReadable()).isFalse();
            assertThat(profile.agentProcessing()).isEqualTo(AgentProcessing.DENIED);
        }
    }

    @Nested
    class Level101 {

        @Test
        void should_useE2eeAndDenyAgentProcessing_when_resolvingLevel101() {
            var profile = resolver.resolve(NoteLevel.of(101));

            assertThat(profile.sync()).isEqualTo(SyncMode.E2EE);
            assertThat(profile.serverReadable()).isFalse();
            assertThat(profile.agentProcessing()).isEqualTo(AgentProcessing.DENIED);
            assertThat(profile.classification()).isEqualTo(Classification.CONFIDENTIAL);
        }
    }

    @Nested
    class UnresolvedLevels {

        @Test
        void should_throwUnresolvedNoteLevelException_when_levelHasNoRegisteredProfileYet() {
            // Level 2..99 sind laut Plan.md Abschnitt 8 bewusst noch offen (Phase 8-Feinschliff) -
            // der Resolver darf hier keine Policy erraten.
            var level = NoteLevel.of(42);

            assertThatThrownBy(() -> resolver.resolve(level))
                .isInstanceOf(UnresolvedNoteLevelException.class)
                .hasMessageContaining("42");
        }
    }

    @Nested
    class CustomRegistration {

        @Test
        void should_returnNewResolverInstance_when_registeringAdditionalProfile_leavingOriginalUnchanged() {
            var customProfile = new NotePolicyProfile(
                1, Classification.INTERNAL, SyncMode.ALLOWED, true, AgentProcessing.DENIED, Sharing.RESTRICTED);

            var extended = resolver.withProfile(NoteLevel.of(2), customProfile);

            assertThat(extended.resolve(NoteLevel.of(2))).isEqualTo(customProfile);
            assertThatThrownBy(() -> resolver.resolve(NoteLevel.of(2)))
                .isInstanceOf(UnresolvedNoteLevelException.class);
        }
    }
}
