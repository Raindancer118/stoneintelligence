package de.raindancer118.stoneintelligence.platform.files;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncFrame;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultSubscriber;
import de.raindancer118.stoneintelligence.platform.vault.FakeFolderRepository;
import de.raindancer118.stoneintelligence.platform.vault.FakeNoteRepository;
import de.raindancer118.stoneintelligence.platform.vault.FolderRegistry;
import de.raindancer118.stoneintelligence.platform.vault.NoteKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileServiceTest {

    @TempDir
    Path storage;

    private final VaultId vaultId = VaultId.newId();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeFileVersionRepository versions = new FakeFileVersionRepository(notes);
    private final FakeFolderRepository folderRepository = new FakeFolderRepository();
    private final VaultAnnouncementService announcements = new VaultAnnouncementService();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-23T10:00:00Z"));
    private final List<String> audit = new ArrayList<>();
    private final List<String> fileAware = new ArrayList<>();
    private final List<String> older = new ArrayList<>();
    private FileSystemBlobStore blobs;
    private FileService files;

    private static VaultSubscriber subscriber(List<String> received, boolean files) {
        return new VaultSubscriber() {
            @Override public String id() { return String.valueOf(System.identityHashCode(received)); }
            @Override public boolean mayRead(String path) { return true; }
            @Override public boolean wantsFileEvents() { return files; }
            @Override public boolean wantsFolderEvents() { return false; }
            @Override public void sendVaultEvent(byte type, NoteId noteId, String path) { received.add(type + " " + path); }
        };
    }

    @BeforeEach
    void setUp() {
        blobs = new FileSystemBlobStore(storage);
        files = new FileService(notes, versions, blobs, new FolderRegistry(folderRepository, announcements), announcements,
            (vault, note, actor, action, payload) -> audit.add(actor + " " + action), new FileLimits(1000, 2500), now::get);
        announcements.subscribe(vaultId, subscriber(fileAware, true));
        announcements.subscribe(vaultId, subscriber(older, false));
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ByteArrayInputStream bytes(int size) {
        return new ByteArrayInputStream(new byte[size]);
    }

    @Test
    void should_createAFile_withItsFolders_andTellOnlyDevicesThatKnowFiles() {
        var file = files.create(vaultId, "Anhänge/Skript.pdf", NoteLevel.of(1), "tom");

        assertThat(file.kind()).isEqualTo(NoteKind.FILE);
        assertThat(folderRepository.list(vaultId)).containsExactly("Anhänge");
        assertThat(fileAware).contains(SyncFrame.TYPE_VAULT_NOTE_CREATED + " Anhänge/Skript.pdf");
        assertThat(older).noneMatch(event -> event.contains("Skript.pdf"));
        assertThat(audit).contains("tom file.created");
    }

    @Test
    void should_refuseNotesPathsHiddenPaths_andLevelsThatDoNotSync() {
        for (var path : List.of("Notiz.md", ".obsidian/workspace.json", "../raus.pdf", "a//b.png", "")) {
            assertThatThrownBy(() -> files.create(vaultId, path, NoteLevel.of(1), "tom"), path).isInstanceOf(FileRefusedException.class);
        }
        assertThatThrownBy(() -> files.create(vaultId, "x.pdf", NoteLevel.of(100), "tom")).isInstanceOf(FileRefusedException.class);
        assertThatThrownBy(() -> files.create(vaultId, "x.pdf", NoteLevel.of(101), "tom")).isInstanceOf(FileRefusedException.class);
    }

    @Test
    void should_storeNewVersions_andHandThemOutAgain() throws Exception {
        var file = files.create(vaultId, "a.pdf", NoteLevel.of(1), "tom");

        var first = files.upload(vaultId, file.id(), 0, bytes("%PDF eins"), "application/pdf", "tom");
        var second = files.upload(vaultId, file.id(), 1, bytes("%PDF zwei"), "application/pdf", "anna");

        assertThat(first.revision()).isEqualTo(1);
        assertThat(second.revision()).isEqualTo(2);
        try (var download = files.download(vaultId, file.id())) {
            assertThat(download.version().revision()).isEqualTo(2);
            assertThat(new String(download.content().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("%PDF zwei");
        }
        assertThat(fileAware).contains(SyncFrame.TYPE_VAULT_NOTE_UPDATED + " a.pdf");
        assertThat(older).noneMatch(event -> event.contains("a.pdf"));
        assertThat(audit).contains("anna file.content-updated");
    }

    @Test
    void should_refuseAChangeOnAnOutdatedBase_withTheCurrentRevision() {
        var file = files.create(vaultId, "a.pdf", NoteLevel.of(1), "tom");
        files.upload(vaultId, file.id(), 0, bytes("eins"), "application/pdf", "tom");

        assertThatThrownBy(() -> files.upload(vaultId, file.id(), 0, bytes("zwei"), "application/pdf", "anna"))
            .isInstanceOf(FileRevisionConflictException.class);
    }

    @Test
    void should_enforceTheSizeLimit_andTheVaultQuota() {
        var big = files.create(vaultId, "gross.mp4", NoteLevel.of(1), "tom");
        assertThatThrownBy(() -> files.upload(vaultId, big.id(), 0, bytes(1001), "video/mp4", "tom"))
            .isInstanceOf(BlobTooLargeException.class);

        files.upload(vaultId, files.create(vaultId, "a.bin", NoteLevel.of(1), "tom").id(), 0, bytes(900), "application/octet-stream", "tom");
        files.upload(vaultId, files.create(vaultId, "b.bin", NoteLevel.of(1), "tom").id(), 0, bytes(900), "application/octet-stream", "tom");
        var third = files.create(vaultId, "c.bin", NoteLevel.of(1), "tom");

        assertThatThrownBy(() -> files.upload(vaultId, third.id(), 0, bytes(800), "application/octet-stream", "tom"))
            .isInstanceOf(FileQuotaExceededException.class);
        assertThat(files.upload(vaultId, third.id(), 0, bytes(600), "application/octet-stream", "tom").revision()).isEqualTo(1);
    }

    @Test
    void should_neverTreatANoteAsAFile() {
        var note = notes.create(vaultId, "Notiz.md", NoteLevel.of(1), "tom");

        assertThatThrownBy(() -> files.upload(vaultId, note.id(), 0, bytes("x"), "text/plain", "tom")).isInstanceOf(FileRefusedException.class);
        assertThatThrownBy(() -> files.download(vaultId, note.id())).isInstanceOf(FileRefusedException.class);
        assertThatThrownBy(() -> files.download(VaultId.newId(), note.id()))
            .isInstanceOf(de.raindancer118.stoneintelligence.platform.vault.NoteNotFoundException.class);
    }

    @Test
    void should_acceptOnlyPlausibleContentTypes() {
        var file = files.create(vaultId, "a.bin", NoteLevel.of(1), "tom");

        assertThat(files.upload(vaultId, file.id(), 0, bytes("x"), "text/html\r\nX-Evil: 1", "tom").contentType())
            .isEqualTo("application/octet-stream");
        assertThat(files.upload(vaultId, file.id(), 1, bytes("y"), "image/svg+xml", "tom").contentType()).isEqualTo("image/svg+xml");
    }

    // Datensparsamkeit (ADR 0009 Punkt 6): ersetzte Fassungen und Bytes geloeschter Dateien verschwinden nach 24 h.
    @Test
    void should_removeReplacedAndDeletedContent_afterTheGracePeriod() throws Exception {
        var file = files.create(vaultId, "a.pdf", NoteLevel.of(1), "tom");
        files.upload(vaultId, file.id(), 0, bytes("alt"), "application/pdf", "tom");
        files.upload(vaultId, file.id(), 1, bytes("neu"), "application/pdf", "tom");
        var gone = files.create(vaultId, "weg.pdf", NoteLevel.of(1), "tom");
        files.upload(vaultId, gone.id(), 0, bytes("gelöscht"), "application/pdf", "tom");
        notes.delete(vaultId, gone.id(), "op-1", "tom");
        for (var path : java.nio.file.Files.walk(storage).filter(java.nio.file.Files::isRegularFile).toList()) {
            java.nio.file.Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        }

        now.set(now.get().plus(Duration.ofDays(2)));
        files.purge();

        try (var download = files.download(vaultId, file.id())) {
            assertThat(new String(download.content().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("neu");
        }
        assertThat(versions.referencedHashes()).hasSize(1);
        try (var stored = java.nio.file.Files.walk(storage)) {
            assertThat(stored.filter(java.nio.file.Files::isRegularFile).count()).isEqualTo(1);
        }
    }

    @Test
    void should_reportAnUnavailableStorage_asSuch() {
        var unmounted = new FileService(notes, versions, new FileSystemBlobStore(storage.resolve("fehlt"), false),
            new FolderRegistry(folderRepository, announcements), announcements, (v, n, a, x, p) -> { }, new FileLimits(1000, 2500), now::get);
        var file = unmounted.create(vaultId, "a.pdf", NoteLevel.of(1), "tom");

        assertThatThrownBy(() -> unmounted.upload(vaultId, file.id(), 0, bytes("x"), "application/pdf", "tom"))
            .isInstanceOf(BlobStoreUnavailableException.class);
    }
}
