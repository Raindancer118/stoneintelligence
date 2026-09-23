package de.raindancer118.stoneintelligence.worker.ingest;

import java.nio.file.Path;
import java.util.UUID;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.protection.ProtectionPolicy;
import de.raindancer118.stoneai.vault.IndexedNote;
import de.raindancer118.stoneai.vault.NoteWriteRefusedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformNoteStoreTest {

    private final FakePlatform platform = new FakePlatform();
    private final UUID changeSet = UUID.randomUUID();
    private final Path root = PlatformNoteStore.ROOT;

    private PlatformNoteStore store() {
        return PlatformNoteStore.load(platform, "vault-1", changeSet, 2);
    }

    @Test
    void should_indexTheNotesTheAiMaySee_byTitleAndAliases() throws Exception {
        platform.human("Mathe/Relation.md", "# Relation\n", 1);
        platform.agent = "ki:Gemini";
        platform.create("vault-1", changeSet, "Notizen/ÄR.md", "---\ntitle: Äquivalenzrelation\naliases: [ÄR]\n---\nText\n", 1);

        var index = store().indexable(StoneAiConfig.defaults(), null);

        assertThat(index).extracting(IndexedNote::file, IndexedNote::title)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(root.resolve("Mathe/Relation.md"), "Relation"),
                org.assertj.core.groups.Tuple.tuple(root.resolve("Notizen/ÄR.md"), "Äquivalenzrelation"));
        assertThat(index.get(1).aliases()).containsExactly("ÄR");
    }

    // Erzeugte Notizen erben das Level des Quelldokuments (ADR 0008).
    @Test
    void should_createNewNotes_onTheLevelOfTheDocument() throws Exception {
        var store = store();

        store.write(root.resolve("Notizen/Neu.md"), "neu\n");

        assertThat(platform.notes.values()).singleElement().satisfies(note -> {
            assertThat(note.path()).isEqualTo("Notizen/Neu.md");
            assertThat(note.level()).isEqualTo(2);
            assertThat(note.text()).isEqualTo("neu\n");
        });
        assertThat(store.exists(root.resolve("Notizen/Neu.md"))).isTrue();
        assertThat(store.read(root.resolve("Notizen/Neu.md"))).isEqualTo("neu\n");
    }

    @Test
    void should_updateItsOwnNotes() throws Exception {
        var store = store();
        store.write(root.resolve("Notizen/Neu.md"), "eins\n");

        store.write(root.resolve("Notizen/Neu.md"), "zwei\n");

        assertThat(platform.events).containsExactly("create Notizen/Neu.md", "update Notizen/Neu.md");
    }

    // Toms Vorgabe: KI veraendert standardmaessig keine von Menschen angelegten Notizen.
    @Test
    void should_refuseToChangeHumanNotes_withoutAskingThePlatform() {
        platform.human("Mathe/Relation.md", "# Relation\n", 1);
        var store = store();

        assertThatThrownBy(() -> store.write(root.resolve("Mathe/Relation.md"), "anders\n"))
            .isInstanceOf(NoteWriteRefusedException.class).hasMessageContaining("Menschen");
        assertThat(platform.events).isEmpty();
    }

    @Test
    void should_turnAPlatformRefusal_intoASkippedNote() {
        var store = store();
        platform.human("Notizen/Belegt.md", "x", 1);

        assertThatThrownBy(() -> store.write(root.resolve("Notizen/Belegt2.md/../Belegt.md").normalize(), "y"))
            .isInstanceOf(NoteWriteRefusedException.class);
    }

    @Test
    void should_neverWriteOutsideTheVault() {
        var store = store();

        assertThatThrownBy(() -> store.write(Path.of("/etc/passwd"), "x")).isInstanceOf(NoteWriteRefusedException.class);
    }

    // The AI's own files (earlier originals) are listed like notes - but they are no text to read.
    @Test
    void should_leaveFilesOutOfTheIndex() throws Exception {
        platform.storeFile("v", null, "Anhänge/Alt.pdf", new byte[]{1}, "application/pdf", 1);

        assertThat(store().indexable(StoneAiConfig.defaults(), null)).isEmpty();
    }

    @Test
    void should_storeTheOriginal_whereThePlatformPutIt() throws Exception {
        platform.human("Anhänge/Brief.pdf", "", 1);
        var store = store();

        var stored = store.writeAttachment(root.resolve("Anhänge/Brief.pdf"), new byte[]{1, 2});

        assertThat(stored).isEqualTo(root.resolve("Anhänge/Brief (2).pdf"));
        assertThat(platform.files).containsKey("Anhänge/Brief (2).pdf");
        assertThat(store.exists(stored)).isTrue();
    }
}
