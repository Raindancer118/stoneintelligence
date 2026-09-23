package de.raindancer118.stoneintelligence.platform.files;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.vault.FakeNoteRepository;

/** Spiegelt {@code JdbcFileVersionRepository}; Versionen geloeschter Dateien verschwinden wie per Cascade. */
public final class FakeFileVersionRepository implements FileVersionRepository {

    private final FakeNoteRepository notes;
    private final List<FileVersion> versions = new ArrayList<>();
    private final Map<NoteId, VaultId> vaults = new java.util.HashMap<>();

    public FakeFileVersionRepository(FakeNoteRepository notes) {
        this.notes = notes;
    }

    private synchronized List<FileVersion> live() {
        versions.removeIf(version -> {
            var vault = vaults.get(version.noteId());
            return vault == null || notes.findById(vault, version.noteId()).isEmpty();
        });
        return versions;
    }

    @Override
    public synchronized FileVersion append(NoteId noteId, long expectedRevision, StoredBlob blob, String contentType, String createdBy,
                                           Instant at) {
        var current = current(noteId).map(FileVersion::revision).orElse(0L);
        if (current != expectedRevision) {
            throw new FileRevisionConflictException(current);
        }
        vaults.computeIfAbsent(noteId, id -> notes.vaultOf(id).orElseThrow());
        var version = new FileVersion(noteId, current + 1, blob.sha256(), blob.size(), contentType, createdBy, at);
        versions.add(version);
        return version;
    }

    @Override
    public synchronized Optional<FileVersion> current(NoteId noteId) {
        return live().stream().filter(v -> v.noteId().equals(noteId)).max(Comparator.comparingLong(FileVersion::revision));
    }

    @Override
    public synchronized Map<NoteId, FileVersion> current(Collection<NoteId> noteIds) {
        return noteIds.stream().map(this::current).flatMap(Optional::stream)
            .collect(Collectors.toMap(FileVersion::noteId, version -> version));
    }

    @Override
    public synchronized long usage(VaultId vaultId) {
        return live().stream().filter(v -> vaultId.equals(vaults.get(v.noteId()))).mapToLong(FileVersion::size).sum();
    }

    @Override
    public synchronized int purgeReplaced(Instant olderThan) {
        var replaced = live().stream()
            .filter(v -> v.createdAt().isBefore(olderThan))
            .filter(v -> current(v.noteId()).map(c -> c.revision() != v.revision()).orElse(false))
            .toList();
        versions.removeAll(replaced);
        return replaced.size();
    }

    @Override
    public synchronized Set<String> referencedHashes() {
        return live().stream().map(FileVersion::sha256).collect(Collectors.toSet());
    }
}
