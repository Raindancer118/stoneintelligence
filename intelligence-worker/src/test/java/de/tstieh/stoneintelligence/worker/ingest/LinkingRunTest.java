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
}
