package de.raindancer118.stoneintelligence.platform.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiCapacityBoardTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-25T10:00:00Z"));
    private final AiServiceDirectory services = new AiServiceDirectory(List.of(new AiService("gemini", "Gemini", Set.of(1))));
    private final AiCapacityBoard board = new AiCapacityBoard(() -> services, now::get);

    private static AiCapacityBoard.ProviderReport provider(String name, boolean exhausted, Instant back) {
        return new AiCapacityBoard.ProviderReport(name, 2, exhausted ? 0 : 2, exhausted, back,
            new AiCapacityBoard.Quota(exhausted ? 0 : 900, 1000, null), null, null, Instant.parse("2026-09-25T09:59:00Z"));
    }

    @Test
    void should_knowNothing_beforeTheWorkerReported() {
        var capacity = board.of("gemini");

        assertThat(capacity.reportedAt()).isNull();
        assertThat(capacity.exhausted()).isNull();
        assertThat(capacity.providers()).isEmpty();
    }

    @Test
    void should_showWhatTheWorkerReported() {
        board.report("gemini", List.of(provider("google-gemini", false, null)));

        var capacity = board.of("gemini");
        assertThat(capacity.reportedAt()).isEqualTo(now.get());
        assertThat(capacity.stale()).isFalse();
        assertThat(capacity.exhausted()).isFalse();
        assertThat(capacity.providers()).extracting(AiCapacityBoard.ProviderReport::provider).containsExactly("google-gemini");
    }

    @Test
    void should_callAServiceExhausted_onlyWhenEveryProviderIs_andSayWhenTheFirstIsBack() {
        var soon = now.get().plusSeconds(600);
        var later = now.get().plusSeconds(3600);
        board.report("gemini", List.of(provider("groq", true, later), provider("google-gemini", false, null)));
        assertThat(board.of("gemini").exhausted()).isFalse();
        assertThat(board.of("gemini").availableAgainAt()).isNull();

        board.report("gemini", List.of(provider("groq", true, later), provider("google-gemini", true, soon)));
        assertThat(board.of("gemini").exhausted()).isTrue();
        assertThat(board.of("gemini").availableAgainAt()).isEqualTo(soon);
    }

    @Test
    void should_markAnOldReportStale() {
        board.report("gemini", List.of(provider("google-gemini", false, null)));
        now.set(now.get().plus(AiCapacityBoard.STALE_AFTER).plus(Duration.ofSeconds(1)));

        assertThat(board.of("gemini").stale()).isTrue();
    }

    @Test
    void should_refuseUnknownServices_andImplausibleReports() {
        assertThatThrownBy(() -> board.report("nope", List.of())).isInstanceOf(AiWriteRefusedException.class);
        assertThatThrownBy(() -> board.of("nope")).isInstanceOf(AiWriteRefusedException.class);
        assertThatThrownBy(() -> board.report("gemini", java.util.Collections.nCopies(AiCapacityBoard.MAX_PROVIDERS + 1,
            provider("x", false, null)))).isInstanceOf(AiWriteRefusedException.class);
        assertThatThrownBy(() -> board.report("gemini", List.of(provider("x".repeat(61), false, null))))
            .isInstanceOf(AiWriteRefusedException.class);
    }
}
