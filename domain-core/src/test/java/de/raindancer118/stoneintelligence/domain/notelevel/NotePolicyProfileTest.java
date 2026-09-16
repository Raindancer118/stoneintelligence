package de.raindancer118.stoneintelligence.domain.notelevel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotePolicyProfileTest {

    @Test
    void should_rejectSchemaVersionBelowOne_when_constructed() {
        assertThatThrownBy(() -> new NotePolicyProfile(
            0, Classification.PUBLIC, SyncMode.ALLOWED, true, AgentProcessing.ALLOWED, Sharing.OPEN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_rejectNullClassification_when_constructed() {
        assertThatThrownBy(() -> new NotePolicyProfile(
            1, null, SyncMode.ALLOWED, true, AgentProcessing.ALLOWED, Sharing.OPEN))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_rejectServerReadableTrue_when_syncModeIsE2ee() {
        assertThatThrownBy(() -> new NotePolicyProfile(
            1, Classification.CONFIDENTIAL, SyncMode.E2EE, true, AgentProcessing.DENIED, Sharing.RESTRICTED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("E2EE");
    }

    @Test
    void should_rejectServerReadableTrue_when_syncModeIsDenied() {
        assertThatThrownBy(() -> new NotePolicyProfile(
            1, Classification.INTERNAL, SyncMode.DENIED, true, AgentProcessing.DENIED, Sharing.RESTRICTED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DENIED");
    }

    @Test
    void should_rejectAgentProcessingAllowed_when_serverCannotReadContent() {
        assertThatThrownBy(() -> new NotePolicyProfile(
            1, Classification.CONFIDENTIAL, SyncMode.E2EE, false, AgentProcessing.ALLOWED, Sharing.RESTRICTED))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("agentProcessing");
    }

    @Test
    void should_accept_when_allowedSyncWithServerReadableAndAgentAllowed() {
        var profile = new NotePolicyProfile(
            1, Classification.PUBLIC, SyncMode.ALLOWED, true, AgentProcessing.ALLOWED, Sharing.OPEN);

        assertThat(profile.serverReadable()).isTrue();
    }
}
