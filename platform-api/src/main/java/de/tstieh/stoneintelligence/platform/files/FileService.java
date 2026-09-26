package de.tstieh.stoneintelligence.platform.files;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.ai.AiAuditRecorder;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.vault.FolderRegistry;
import de.tstieh.stoneintelligence.platform.vault.Note;
import de.tstieh.stoneintelligence.platform.vault.NoteKind;
import de.tstieh.stoneintelligence.platform.vault.NoteNotFoundException;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;

/**
 * Dateien im Vault (ADR 0009): anlegen, neue Fassung hochladen, aktuelle Fassung ausliefern,
 * aufraeumen. Berechtigungen prueft der Controller; hier gelten Pfad-, Level-, Groessen- und
 * Platzregeln. Ankuendigungen gehen nur an Geraete, die Dateien kennen.
 */
public class FileService {

    static final Duration GRACE_PERIOD = Duration.ofHours(24);
    private static final Pattern CONTENT_TYPE = Pattern.compile("[a-z0-9][a-z0-9.+-]{0,63}/[a-z0-9][a-z0-9.+-]{0,127}");

    private final NoteRepository notes;
    private final FileVersionRepository versions;
    private final BlobStore blobs;
    private final FolderRegistry folders;
    private final VaultAnnouncementService announcements;
    private final AiAuditRecorder audit;
    private final FileLimits limits;
    private final Supplier<Instant> clock;

    public FileService(NoteRepository notes, FileVersionRepository versions, BlobStore blobs, FolderRegistry folders,
                       VaultAnnouncementService announcements, AiAuditRecorder audit, FileLimits limits, Supplier<Instant> clock) {
        this.notes = notes;
        this.versions = versions;
        this.blobs = blobs;
        this.folders = folders;
        this.announcements = announcements;
        this.audit = audit;
        this.limits = limits;
        this.clock = clock;
    }

    public FileLimits limits() {
        return limits;
    }

    public Note create(VaultId vaultId, String path, NoteLevel level, String actor) {
        if (!FilePaths.isValid(path)) {
            throw new FileRefusedException("Ungültiger Dateipfad: " + path);
        }
        if (level.value() >= NoteLevel.NO_SYNC) {
            throw new FileRefusedException("Dateien mit Level " + level.value() + " werden nicht synchronisiert");
        }
        var file = notes.create(vaultId, path, level, actor, NoteKind.FILE);
        audit.record(vaultId, file.id(), actor, "file.created", Map.of("path", path));
        announcements.announceNoteCreated(vaultId, file.id(), path, NoteKind.FILE);
        folders.ensureParentsOf(vaultId, path, actor);
        return file;
    }

    /**
     * Neue Fassung auf Basis von {@code expectedRevision}. Gelesen wird hoechstens so viel, wie
     * Dateigrenze und freier Platz des Vaults erlauben.
     */
    public FileVersion upload(VaultId vaultId, NoteId noteId, long expectedRevision, InputStream content, String contentType,
                              String actor) {
        var file = file(vaultId, noteId);
        var remaining = Math.max(0, limits.vaultQuotaBytes() - versions.usage(vaultId));
        var limit = Math.min(limits.maxFileBytes(), remaining);
        StoredBlob blob;
        try {
            blob = blobs.put(content, limit);
        } catch (BlobTooLargeException tooLarge) {
            throw remaining < limits.maxFileBytes() ? new FileQuotaExceededException(limits.vaultQuotaBytes()) : tooLarge;
        }
        var version = versions.append(noteId, expectedRevision, blob, sanitized(contentType), actor, clock.get());
        audit.record(vaultId, noteId, actor, "file.content-updated", Map.of("revision", version.revision(), "size", version.size()));
        notes.markEdited(vaultId, noteId, actor, version.createdAt());
        announcements.announceFileUpdated(vaultId, noteId, file.path());
        return version;
    }

    /** @throws FileRefusedException wenn es noch keine Fassung gibt */
    public FileDownload download(VaultId vaultId, NoteId noteId) {
        var file = file(vaultId, noteId);
        var version = versions.current(noteId)
            .orElseThrow(() -> new FileRefusedException("Zu dieser Datei wurde noch kein Inhalt hochgeladen"));
        return new FileDownload(file, version, blobs.open(version.sha256()));
    }

    public java.util.Optional<FileVersion> current(NoteId noteId) {
        return versions.current(noteId);
    }

    public Map<NoteId, FileVersion> current(java.util.Collection<NoteId> noteIds) {
        return versions.current(noteIds);
    }

    /** Ersetzte Fassungen und nicht mehr referenzierte Bytes nach der Karenzzeit entfernen. */
    public int purge() {
        var cutoff = clock.get().minus(GRACE_PERIOD);
        var purged = versions.purgeReplaced(cutoff);
        if (blobs.available()) {
            purged += blobs.deleteUnreferenced(versions.referencedHashes(), cutoff);
        }
        return purged;
    }

    private Note file(VaultId vaultId, NoteId noteId) {
        var note = notes.findById(vaultId, noteId).orElseThrow(() -> new NoteNotFoundException(vaultId, noteId));
        if (!note.isFile()) {
            throw new FileRefusedException(note.path() + " ist eine Notiz, keine Datei");
        }
        return note;
    }

    private static String sanitized(String contentType) {
        var type = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
        return CONTENT_TYPE.matcher(type).matches() ? type : "application/octet-stream";
    }
}
