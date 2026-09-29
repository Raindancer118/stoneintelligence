package de.tstieh.stoneintelligence.platform.vault;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncScopeTest {

    @Test
    void should_meanTheWholeVault_whenEmptyOrContainingTheRoot() {
        assertThat(SyncScope.of(List.of()).isWholeVault()).isTrue();
        assertThat(SyncScope.of(List.of("Team", "/")).isWholeVault()).isTrue();
        assertThat(SyncScope.of(List.of()).covers("irgendwo/x.md")).isTrue();
    }

    @Test
    void should_keepOnlyTheOutermostAreas_normalized() {
        assertThat(SyncScope.of(List.of("/Team/", "Team/Protokolle", "Kunden", "Kunden/Archiv/alt.md", "Teamraum")).areas())
            .containsExactly("Kunden", "Team", "Teamraum");
    }

    @Test
    void should_coverEntriesInsideAnArea_orExactlyAPinnedNote() {
        var scope = SyncScope.of(List.of("Team", "Kunden/Vertrag.md"));

        assertThat(scope.covers("Team/plan.md")).isTrue();
        assertThat(scope.covers("Team/Sub/x.md")).isTrue();
        assertThat(scope.covers("Teamraum/x.md")).isFalse();
        assertThat(scope.covers("Kunden/Vertrag.md")).isTrue();
        assertThat(scope.covers("Kunden/Angebot.md")).isFalse();
    }

    // Damit die Ordnerstruktur ueber einem Bereich lokal existiert, zaehlen auch dessen Eltern.
    @Test
    void should_touchFoldersInsideAndAboveAnArea() {
        var scope = SyncScope.of(List.of("Abteilungen/Vertrieb/Nord"));

        assertThat(scope.touchesFolder("Abteilungen")).isTrue();
        assertThat(scope.touchesFolder("Abteilungen/Vertrieb")).isTrue();
        assertThat(scope.touchesFolder("Abteilungen/Vertrieb/Nord/2026")).isTrue();
        assertThat(scope.touchesFolder("Abteilungen/Einkauf")).isFalse();
    }

    @Test
    void should_limitTheNumberOfAreas() {
        var many = java.util.stream.IntStream.range(0, SyncScope.MAX_AREAS + 1).mapToObj(i -> "Bereich" + i).toList();

        assertThatThrownBy(() -> SyncScope.of(many)).isInstanceOf(IllegalArgumentException.class);
    }
}
