package de.raindancer118.stoneintelligence.platform.ai;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.platform.files.FakeFileVersionRepository;
import de.raindancer118.stoneintelligence.platform.files.FileLimits;
import de.raindancer118.stoneintelligence.platform.files.FileService;
import de.raindancer118.stoneintelligence.platform.files.FileSystemBlobStore;
import de.raindancer118.stoneintelligence.platform.sync.relay.FakeSnapshotStore;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncRoomRegistry;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.raindancer118.stoneintelligence.platform.sync.yjs.YjsBridge;
import de.raindancer118.stoneintelligence.platform.vault.FakeFolderRepository;
import de.raindancer118.stoneintelligence.platform.vault.FakeNoteRepository;
import de.raindancer118.stoneintelligence.platform.vault.FolderRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The document the AI read is kept in the vault as a real, synchronised file - so its source
 * note can link it - and it is part of the change set: undoing the run removes it again.
 */
class AiOriginalFileTest {

    private static final AiService EXTERN = new AiService("extern", "Extern", java.util.Set.of(1, 2));

    private static YjsBridge yjs;

    @BeforeAll
    static void startYjs() {
        yjs = YjsBridge.load();
    }

    @AfterAll
    static void stopYjs() {
        yjs.close();
    }

    @TempDir
    Path storage;

    private final VaultId vaultId = VaultId.newId();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeSnapshotStore snapshots = new FakeSnapshotStore();
    private final FakeFolderRepository folderRepository = new FakeFolderRepository();
    private final VaultAnnouncementService announcements = new VaultAnnouncementService();
    private final FakeAiChangeSetRepository changeSets = new FakeAiChangeSetRepository();
    private FileService files;
    private AiWriteService service;

    @BeforeEach
    void setUp() {
        var folders = new FolderRegistry(folderRepository, announcements, new de.raindancer118.stoneintelligence.platform.identity.FakeAccessGrantRepository(notes));
        files = new FileService(notes, new FakeFileVersionRepository(notes), new FileSystemBlobStore(storage), folders,
            announcements, (vault, note, actor, action, payload) -> { }, new FileLimits(1000, 2500), Instant::now);
        service = new AiWriteService(notes, snapshots, new SyncRelayService(snapshots, new SyncRoomRegistry()), yjs,
            announcements, folders, (vault, note, actor, action, payload) -> { }, new AiServiceDirectory(List.of(EXTERN)),
            changeSets, Instant::now, files);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private AiChangeSet changeSet() {
        return service.startChangeSet(vaultId, EXTERN, "tom", "Brief.pdf");
    }

    @Test
    void should_storeTheOriginalAsASynchronisedFile() throws Exception {
        var changeSet = changeSet();

        var stored = service.storeFile(vaultId, changeSet.id(), "Anhänge/Brief.pdf", bytes("%PDF-1"), "application/pdf",
            NoteLevel.of(1));

        assertThat(stored.path()).isEqualTo("Anhänge/Brief.pdf");
        try (var content = files.download(vaultId, stored.noteId()).content()) {
            assertThat(content.readAllBytes()).isEqualTo(bytes("%PDF-1"));
        }
        assertThat(folderRepository.list(vaultId)).contains("Anhänge");
    }

    // The same letter uploaded twice is one file, not "Brief (2).pdf".
    @Test
    void should_reuseTheFile_whenTheSameDocumentIsAlreadyThere() {
        var first = service.storeFile(vaultId, changeSet().id(), "Anhänge/Brief.pdf", bytes("%PDF-1"), "application/pdf", NoteLevel.of(1));

        var second = service.storeFile(vaultId, changeSet().id(), "Anhänge/Brief.pdf", bytes("%PDF-1"), "application/pdf", NoteLevel.of(1));

        assertThat(second.noteId()).isEqualTo(first.noteId());
    }

    @Test
    void should_pickAFreeName_whenAnotherDocumentHasTheName() {
        var tom = files.create(vaultId, "Anhänge/Brief.pdf", NoteLevel.of(1), "tom");
        files.upload(vaultId, tom.id(), 0, new ByteArrayInputStream(bytes("Toms Brief")), "application/pdf", "tom");

        var stored = service.storeFile(vaultId, changeSet().id(), "Anhänge/Brief.pdf", bytes("%PDF-1"), "application/pdf",
            NoteLevel.of(1));

        assertThat(stored.path()).isEqualTo("Anhänge/Brief (2).pdf");
    }

    @Test
    void should_removeTheOriginal_whenTheRunIsUndone() {
        var changeSet = changeSet();
        var stored = service.storeFile(vaultId, changeSet.id(), "Anhänge/Brief.pdf", bytes("%PDF-1"), "application/pdf",
            NoteLevel.of(1));

        var report = service.revert(vaultId, changeSet.id(), "tom");

        assertThat(report.reverted()).isEqualTo(1);
        assertThat(notes.findById(vaultId, stored.noteId())).isEmpty();
        assertThat(folderRepository.list(vaultId)).doesNotContain("Anhänge");
    }

    @Test
    void should_keepTheOriginal_whenSomeoneReplacedItSince() {
        var changeSet = changeSet();
        var stored = service.storeFile(vaultId, changeSet.id(), "Anhänge/Brief.pdf", bytes("%PDF-1"), "application/pdf",
            NoteLevel.of(1));
        files.upload(vaultId, stored.noteId(), 1, new ByteArrayInputStream(bytes("mit Anmerkungen")), "application/pdf", "tom");

        var report = service.revert(vaultId, changeSet.id(), "tom");

        assertThat(report.conflicts()).singleElement().satisfies(conflict -> assertThat(conflict.path()).isEqualTo("Anhänge/Brief.pdf"));
        assertThat(notes.findById(vaultId, stored.noteId())).isPresent();
    }

    @Test
    void should_refuseAFile_theServiceMayNotProcessAtThatLevel() {
        assertThatThrownBy(() -> service.storeFile(vaultId, changeSet().id(), "Anhänge/Brief.pdf", bytes("x"), "application/pdf",
            NoteLevel.of(5))).isInstanceOf(AiWriteRefusedException.class);
    }
}
