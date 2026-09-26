package de.tstieh.stoneintelligence.platform.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.files.FileService;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.stereotype.Component;

/**
 * "Mit KI einlesen" fuer Dateien, die schon im Vault liegen (ADR 0011) - Obsidian muss eine PDF
 * dafuer nicht noch einmal hochladen. Jede Datei wird mit IHREM Level eingereiht, nicht mit einem
 * vom Aufrufer gewaehlten: sonst liesse sich ein geschuetztes Dokument als Level 1 ausgeben.
 */
@Component
public class VaultFileJobs {

    private final FileService files;
    private final de.tstieh.stoneintelligence.platform.vault.NoteRepository notes;
    private final VaultAccessGuard access;
    private final AiJobService jobs;

    public VaultFileJobs(FileService files, de.tstieh.stoneintelligence.platform.vault.NoteRepository notes,
                         VaultAccessGuard access, AiJobService jobs) {
        this.files = files;
        this.notes = notes;
        this.access = access;
        this.jobs = jobs;
    }

    public List<AiJob> start(VaultId vaultId, String actor, String serviceId, List<NoteId> fileIds) {
        access.require(vaultId, actor, Permission.CREATE);
        if (fileIds.isEmpty() || fileIds.size() > AiJobService.MAX_FILES_PER_UPLOAD) {
            throw new AiWriteRefusedException("1 bis " + AiJobService.MAX_FILES_PER_UPLOAD + " Dateien auf einmal");
        }
        var created = new ArrayList<AiJob>();
        for (var fileId : fileIds) {
            // Erst das Leserecht, dann die Datei: sonst verriete schon die Fehlermeldung etwas ueber sie.
            var entry = notes.findById(vaultId, fileId)
                .orElseThrow(() -> new de.tstieh.stoneintelligence.platform.vault.NoteNotFoundException(vaultId, fileId));
            access.require(vaultId, actor, Permission.READ, entry.path());
            try (var download = files.download(vaultId, fileId)) {
                var note = download.note();
                // Vor dem Einlesen in den Speicher - die Grenze selbst prueft AiJobService.
                if (download.version().size() > AiJobService.MAX_FILE_BYTES) {
                    throw new AiWriteRefusedException(note.path() + " ist größer als " + AiJobService.MAX_FILE_BYTES / (1024 * 1024) + " MB");
                }
                var name = note.path().substring(note.path().lastIndexOf('/') + 1);
                created.addAll(jobs.upload(vaultId, actor, serviceId, note.level().value(),
                    List.of(new AiJobService.Upload(name, download.version().contentType(), download.content().readAllBytes()))));
            } catch (IOException e) {
                throw new UncheckedIOException("could not read " + fileId.value(), e);
            }
        }
        return created;
    }
}
