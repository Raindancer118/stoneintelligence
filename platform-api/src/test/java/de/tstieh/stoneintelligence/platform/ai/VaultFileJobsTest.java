package de.tstieh.stoneintelligence.platform.ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.files.FakeFileVersionRepository;
import de.tstieh.stoneintelligence.platform.files.FileLimits;
import de.tstieh.stoneintelligence.platform.files.FileService;
import de.tstieh.stoneintelligence.platform.files.FileSystemBlobStore;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.vault.FakeFolderRepository;
import de.tstieh.stoneintelligence.platform.vault.FakeNoteRepository;
import de.tstieh.stoneintelligence.platform.vault.FolderRegistry;
import de.tstieh.stoneintelligence.platform.vault.ForbiddenException;
import de.tstieh.stoneintelligence.platform.vault.Note;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultFileJobsTest {

    @TempDir
    Path storage;

    private final VaultId vaultId = VaultId.newId();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes);
    private final FakeAiJobRepository jobRepository = new FakeAiJobRepository();
    private final List<String> started = new ArrayList<>();
    private VaultFileJobs fileJobs;
    private FileService files;

    @BeforeEach
    void setUp() {
        var announcements = new VaultAnnouncementService();
        files = new FileService(notes, new FakeFileVersionRepository(notes), new FileSystemBlobStore(storage),
            new FolderRegistry(new FakeFolderRepository(), announcements, grants), announcements,
            (vault, note, actor, action, payload) -> { }, new FileLimits(10_000, 100_000), Instant::now);
        var services = new AiServiceDirectory(List.of(new AiService("gemini", "Gemini", Set.of(1))));
        var jobs = new AiJobService(jobRepository, () -> services, (vault, service, requestedBy, label) -> {
            started.add(label);
            return UUID.randomUUID();
        }, (vault, changeSet, actor) -> { }, Instant::now);
        fileJobs = new VaultFileJobs(files, notes, new VaultAccessGuard(authorization, grants), jobs);
        var role = authorization.createRole(vaultId, "editor", Set.of(Permission.READ, Permission.WRITE, Permission.CREATE));
        var group = authorization.createGroup(vaultId, "editors");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "anna");
    }

    private Note storedFile(String path, int level, String content) {
        var file = files.create(vaultId, path, NoteLevel.of(level), "anna");
        files.upload(vaultId, file.id(), 0, new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), "application/pdf", "anna");
        return file;
    }

    @Test
    void should_queueAFileThatIsAlreadyInTheVault_withItsOwnLevel() {
        var skript = storedFile("Anhänge/Skript.pdf", 1, "%PDF-1.7 Inhalt");

        var created = fileJobs.start(vaultId, "anna", "gemini", List.of(skript.id()));

        assertThat(created).singleElement().satisfies(job -> {
            assertThat(job.fileName()).isEqualTo("Skript.pdf");
            assertThat(job.level()).isEqualTo(1);
        });
    }

    @Test
    void should_refuseFilesTheServiceMayNotProcess_orThePersonMayNotRead() {
        var geheim = storedFile("Privat/geheim.pdf", 2, "%PDF-1.7 geheim");
        var hidden = storedFile("Team/hidden.pdf", 1, "%PDF-1.7 versteckt");
        grants.put(vaultId, GrantTarget.folder("Team"), GrantScope.user("anna"), Set.of(), "tom");

        assertThatThrownBy(() -> fileJobs.start(vaultId, "anna", "gemini", List.of(geheim.id())))
            .isInstanceOf(AiWriteRefusedException.class);
        assertThatThrownBy(() -> fileJobs.start(vaultId, "anna", "gemini", List.of(hidden.id())))
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> fileJobs.start(vaultId, "mallory", "gemini", List.of(hidden.id())))
            .isInstanceOf(ForbiddenException.class);
        assertThat(jobRepository.list(vaultId, 10)).isEmpty();
    }
}
