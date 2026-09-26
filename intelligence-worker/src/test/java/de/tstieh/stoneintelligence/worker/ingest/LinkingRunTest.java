package de.tstieh.stoneintelligence.worker.ingest;

import java.util.UUID;
import de.tstieh.stoneintelligence.worker.platform.ClaimedJob;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkingRunTest {

    private final FakePlatform platform = new FakePlatform();

    private static ClaimedJob linkingJob() {
        return new ClaimedJob(UUID.randomUUID(), "vault-1", "lokal", "tom", "Verlinkung", "text/plain", 0, 1, UUID.randomUUID(), 1,
            "LINKING");
    }

    private String text(String path) {
        return platform.notes.values().stream().filter(n -> n.path().equals(path)).findFirst().orElseThrow().text();
    }

    @Test
    void should_linkLiteralMentionsOfTitlesAndAliases_acrossTheVault() {
        platform.human("Biologie/Photosynthese.md", "---\naliases: [Fotosynthese]\n---\n# Photosynthese\n", 1);
        platform.human("Licht.md", "# Licht\n", 1);
        platform.human("Pflanzen.md", "Pflanzen betreiben Fotosynthese mit Licht.\n", 1);
        var pdf = UUID.randomUUID().toString();
        platform.notes.put(pdf, new FakePlatform.Stored(pdf, "Anhang.pdf", 1, "tom", null, "FILE"));

        new LinkingRun(platform).run(linkingJob());

        assertThat(text("Pflanzen.md")).isEqualTo("Pflanzen betreiben [[Photosynthese|Fotosynthese]] mit [[Licht]].\n");
        assertThat(text("Licht.md")).isEqualTo("# Licht\n");
        assertThat(platform.events).contains("link Pflanzen.md 2")
            .anySatisfy(event -> assertThat(event).startsWith("progress 100").contains("2 Links in 1 Notiz"));
        assertThat(platform.events).last().isEqualTo("complete");
    }

    @Test
    void should_askTheServerOnlyForNotesThatMentionSomething() {
        platform.human("Licht.md", "# Licht\n", 1);
        platform.human("Leer.md", "Nichts Verwandtes hier.\n", 1);

        new LinkingRun(platform).run(linkingJob());

        assertThat(platform.events).noneMatch(event -> event.startsWith("link"));
        assertThat(platform.events).anySatisfy(event -> assertThat(event).contains("Keine neuen Links"));
    }

    // ADR 0012, Stufe 2: Vektoren nur fuer Neues oder Geaendertes; aehnliche Inhalte nur im Modus SEMANTIC.
    @org.junit.jupiter.api.Nested
    class Semantisch {

        private final WordEmbedder embedder = new WordEmbedder();

        private LinkingRun run() {
            return new LinkingRun(platform, embedder, new LinkingRun.Thresholds(0.5, 0.99));
        }

        @Test
        void should_indexEveryNoteOnce_andAgainOnlyWhenItChanged() {
            platform.human("Licht.md", "# Licht\nPhotonen Wellen Energie\n", 1);
            platform.human("Pflanzen.md", "# Pflanzen\nChlorophyll Blätter Wurzeln\n", 1);

            run().run(linkingJob());
            var afterFirst = embedder.calls;
            run().run(linkingJob());

            assertThat(platform.embeddingStates).hasSize(2);
            assertThat(afterFirst).isPositive();
            assertThat(embedder.calls).as("nothing changed, nothing re-embedded").isEqualTo(afterFirst);

            var licht = platform.notes.values().stream().filter(n -> n.path().equals("Licht.md")).findFirst().orElseThrow();
            platform.notes.put(licht.noteId(), new FakePlatform.Stored(licht.noteId(), "Licht.md", 1, "tom", "# Licht\nNeuer Absatz über Farben\n"));
            run().run(linkingJob());
            assertThat(platform.events.stream().filter(e -> e.equals("embed Licht.md")).count()).isEqualTo(2);
            assertThat(platform.events.stream().filter(e -> e.equals("embed Pflanzen.md")).count()).isEqualTo(1);
        }

        @Test
        void should_linkSimilarContent_underRelated_onlyInSemanticMode() {
            platform.human("Photosynthese.md", "# Photosynthese\nChlorophyll wandelt Sonnenlicht Energie Zucker\n", 1);
            platform.human("Blatt.md", "# Blatt\nChlorophyll wandelt Sonnenlicht Energie Stärke\n", 1);
            platform.human("Steuer.md", "# Steuer\nUmsatzsteuer Vorsteuer Rechnung Finanzamt\n", 1);

            run().run(linkingJob());
            assertThat(text("Blatt.md")).doesNotContain("[[");

            platform.linkingMode = "SEMANTIC";
            run().run(linkingJob());

            assertThat(text("Blatt.md")).contains("## Verwandt").contains("- [[Photosynthese]]").doesNotContain("Steuer");
            assertThat(text("Steuer.md")).doesNotContain("[[");
        }

        @Test
        void should_skipNearDuplicates() {
            platform.linkingMode = "SEMANTIC";
            platform.human("Kopie A.md", "# Kopie\nChlorophyll wandelt Sonnenlicht Energie\n", 1);
            platform.human("Kopie B.md", "# Kopie\nChlorophyll wandelt Sonnenlicht Energie\n", 1);

            run().run(linkingJob());

            assertThat(text("Kopie A.md")).doesNotContain("[[");
        }
    }
}
