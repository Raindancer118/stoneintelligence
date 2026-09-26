package de.raindancer118.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.platform.audit.AuditService;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import de.raindancer118.stoneintelligence.platform.sync.relay.SnapshotStore;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ID-first (Plan.md Abschnitt 3, Fehlerklasse 5): jede Mutation adressiert die {@link NoteId},
 * nie den Pfad. Der Actor kommt seit Phase 3 (ADR 0006 Punkt 6, {@code SecurityConfig}) aus dem
 * authentifizierten OIDC-Principal ({@code Authentication#getName()}, gemappt auf
 * {@code preferred_username}), nicht mehr aus einem client-behaupteten Header.
 */
@RestController
public class NoteController {

    private final NoteRepository notes;
    private final SyncRelayService relay;
    private final AuditService audit;
    private final VaultAccessGuard access;
    private final VaultAnnouncementService announcements;
    private final SnapshotStore snapshots;
    private final FolderRegistry folders;
    private final de.raindancer118.stoneintelligence.platform.files.FileVersionRepository fileVersions;

    public NoteController(NoteRepository notes, SyncRelayService relay, AuditService audit, VaultAccessGuard access,
                          VaultAnnouncementService announcements, SnapshotStore snapshots, FolderRegistry folders,
                          de.raindancer118.stoneintelligence.platform.files.FileVersionRepository fileVersions) {
        this.folders = folders;
        this.fileVersions = fileVersions;
        this.notes = notes;
        this.snapshots = snapshots;
        this.relay = relay;
        this.audit = audit;
        this.access = access;
        this.announcements = announcements;
    }

    @PostMapping("/api/v1/vaults/{vaultId}/notes")
    @Transactional
    public NoteResponse create(
        @PathVariable String vaultId,
        @RequestBody CreateNoteRequest request,
        Authentication authentication
    ) {
        var actor = authentication.getName();
        var vId = VaultId.of(vaultId);
        validatePath(request.path());
        access.require(vId, actor, Permission.CREATE, request.path());
        var note = notes.create(vId, request.path(), NoteLevel.of(request.noteLevel()), actor);
        audit.record(vId, note.id(), actor, "note.created", java.util.Map.of("path", note.path()));
        // Sofort an alle verbundenen Geraete des Vaults - ohne das erfuehren sie von einer auf
        // einem anderen Geraet angelegten Notiz erst beim naechsten vollstaendigen Abgleich.
        announcements.announceNoteCreated(vId, note.id(), note.path());
        folders.ensureParentsOf(vId, note.path(), actor);
        return NoteResponse.from(note);
    }

    @GetMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/audit")
    public List<AuditEventResponse> auditTrail(
        @PathVariable String vaultId, @PathVariable String noteId, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        access.requireMember(vId, authentication.getName());
        notes.findById(vId, NoteId.of(noteId)).ifPresent(note ->
            access.require(vId, authentication.getName(), Permission.READ, note.path()));
        var events = audit.listForNote(vId, NoteId.of(noteId));
        // Auch nach Loeschung bleibt der Verlauf abrufbar, aber nie fuer gesperrte historische Pfade.
        var historicalPaths = events.stream().flatMap(event -> java.util.stream.Stream.of("path", "from", "to")
            .map(event.payload()::get).filter(String.class::isInstance).map(String.class::cast)).distinct().toList();
        access.requireReadablePaths(vId, authentication.getName(), historicalPaths);
        return events.stream()
            .map(AuditEventResponse::from)
            .toList();
    }

    @GetMapping("/api/v1/vaults/{vaultId}/notes/{noteId}")
    public ResponseEntity<NoteResponse> get(
        @PathVariable String vaultId, @PathVariable String noteId, Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        access.requireMember(vId, authentication.getName());
        return notes.findById(vId, NoteId.of(noteId))
            .map(note -> {
                access.require(vId, authentication.getName(), Permission.READ, note.path());
                return NoteResponse.from(note);
            })
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Fehlerklasse 2 (Plan.md Abschnitt 3): jede Antwort traegt eine ueber alle Seiten stabile
     * {@code epochId} und ein {@code complete}-Flag - der Client darf eine unvollstaendige
     * Antwort NIE als Grundlage fuer lokale Loeschungen verwenden.
     *
     * <p>Freigaben filtern den Inhalt jeder Seite (ADR 0011) - es genuegt Mitgliedschaft. Cursor und complete beziehen sich weiterhin
     * auf den serverseitigen Durchlauf; eine gefilterte leere Seite kann deshalb unvollstaendig sein.
     */
    @GetMapping("/api/v1/vaults/{vaultId}/notes")
    public ReconciliationResponse reconcile(
        @PathVariable String vaultId,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "100") int pageSize,
        @RequestParam(defaultValue = "note") String kinds,
        Authentication authentication
    ) {
        var vId = VaultId.of(vaultId);
        access.requireMember(vId, authentication.getName());
        var page = notes.list(vId, cursor, pageSize, parseKinds(kinds));
        var readable = access.readableNotes(vId, authentication.getName(), page.notes());
        var revisions = snapshots.latestRevisions(readable.stream().filter(note -> !note.isFile()).map(Note::id).toList());
        var files = fileVersions.current(readable.stream().filter(Note::isFile).map(Note::id).toList());
        return new ReconciliationResponse(
            page.epochId(), page.complete(), page.nextCursor().orElse(null),
            readable.stream().map(note -> note.isFile()
                ? ListedNoteResponse.fromFile(note, files.get(note.id()))
                : ListedNoteResponse.from(note, revisions.getOrDefault(note.id(), 0L))).toList());
    }

    /** {@code note} (Standard, auch fuer aeltere Clients) oder {@code note,file} - ADR 0009 Punkt 5. */
    private static java.util.Set<NoteKind> parseKinds(String kinds) {
        var parsed = java.util.EnumSet.noneOf(NoteKind.class);
        for (var kind : kinds.split(",")) {
            switch (kind.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "note" -> parsed.add(NoteKind.NOTE);
                case "file" -> parsed.add(NoteKind.FILE);
                default -> throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Unknown kind: " + kind);
            }
        }
        return parsed;
    }

    @PatchMapping("/api/v1/vaults/{vaultId}/notes/{noteId}")
    @Transactional
    public NoteResponse rename(
        @PathVariable String vaultId,
        @PathVariable String noteId,
        @RequestBody RenameNoteRequest request,
        Authentication authentication
    ) {
        var actor = authentication.getName();
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        var existing = notes.findById(vId, nId);
        // Eine Notiz bleibt Markdown, eine Datei wird nie zu Markdown (ADR 0009).
        if (existing.map(Note::isFile).orElse(false)) {
            if (!de.raindancer118.stoneintelligence.platform.files.FilePaths.isValid(request.path())) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Invalid file path");
            }
        } else {
            validatePath(request.path());
        }
        var before = existing.map(Note::path).orElse(null);
        access.require(vId, actor, Permission.WRITE, before != null ? before : request.path());
        access.require(vId, actor, Permission.WRITE, request.path());
        var note = notes.rename(vId, nId, request.path());
        audit.record(vId, nId, actor, "note.renamed", java.util.Map.of("from", String.valueOf(before), "to", note.path()));
        announcements.announceNoteRenamed(vId, nId, note.path(), note.kind());
        folders.ensureParentsOf(vId, note.path(), actor);
        return NoteResponse.from(note);
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/notes/{noteId}")
    @Transactional
    public TombstoneResponse delete(
        @PathVariable String vaultId,
        @PathVariable String noteId,
        @RequestHeader("X-Operation-Id") String operationId,
        Authentication authentication
    ) {
        var actor = authentication.getName();
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        var existing = notes.findById(vId, nId);
        var path = existing.map(Note::path).orElse(null);
        if (path != null) {
            access.require(vId, actor, Permission.DELETE, path);
        } else {
            access.require(vId, actor, Permission.DELETE);
        }
        var tombstone = notes.delete(vId, nId, operationId, actor);
        audit.record(vId, nId, actor, "note.deleted", java.util.Map.of("operationId", operationId));
        // Erst NACH den DB-Schreibvorgaengen ankuendigen, dass die Note weg ist - sonst wuerde
        // ein Client benachrichtigt, bevor der Audit-Eintrag (oder gar die Loeschung selbst bei
        // einem spaeteren Rollback) tatsaechlich feststeht.
        relay.onNoteDeleted(nId);
        // `relay.onNoteDeleted` erreicht nur Sessions, die genau diese Notiz gejoint haben - seit
        // das Plugin nur noch geoeffnete Notizen joint, ist das im Regelfall niemand. Die
        // vault-weite Ankuendigung ist der Weg, auf dem die Loeschung die anderen Geraete
        // ueberhaupt erreicht.
        if (path != null) {
            announcements.announceNoteDeleted(vId, nId, path, existing.get().kind());
        }
        return TombstoneResponse.from(tombstone);
    }

    private static void validatePath(String path) {
        if (!NotePaths.isValid(path)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "Invalid Markdown path");
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<Void> duplicatePath() {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).build();
    }

    public record CreateNoteRequest(String path, int noteLevel) {
    }

    public record RenameNoteRequest(String path) {
    }

    public record NoteResponse(String id, String vaultId, String path, int noteLevel, String createdBy, Instant createdAt,
                               NoteKind kind) {
        public static NoteResponse from(Note note) {
            return new NoteResponse(
                note.id().value().toString(), note.vaultId().value().toString(),
                note.path(), note.level().value(), note.createdBy(), note.createdAt(), note.kind());
        }
    }

    public record TombstoneResponse(String noteId, String operationId, long serverSequence, String deletedBy, Instant deletedAt) {
        static TombstoneResponse from(Tombstone tombstone) {
            return new TombstoneResponse(
                tombstone.noteId().value().toString(), tombstone.operationId(),
                tombstone.serverSequence(), tombstone.deletedBy(), tombstone.deletedAt());
        }
    }

    /** Wie {@link NoteResponse}, plus {@code revision}: hoechste gespeicherte Update-Sequenz (0 = noch kein Inhalt). */
    /** Fuer Dateien ist {@code revision} die Fassung (0 = noch kein Inhalt), dazu Hash und Groesse. */
    public record ListedNoteResponse(String id, String vaultId, String path, int noteLevel, String createdBy,
                                     Instant createdAt, long revision, NoteKind kind, String sha256, Long size) {
        static ListedNoteResponse from(Note note, long revision) {
            return new ListedNoteResponse(
                note.id().value().toString(), note.vaultId().value().toString(),
                note.path(), note.level().value(), note.createdBy(), note.createdAt(), revision, note.kind(), null, null);
        }

        static ListedNoteResponse fromFile(Note note, de.raindancer118.stoneintelligence.platform.files.FileVersion version) {
            return new ListedNoteResponse(
                note.id().value().toString(), note.vaultId().value().toString(), note.path(), note.level().value(),
                note.createdBy(), note.createdAt(), version == null ? 0 : version.revision(), note.kind(),
                version == null ? null : version.sha256(), version == null ? null : version.size());
        }
    }

    public record ReconciliationResponse(java.util.UUID epochId, boolean complete, String nextCursor, List<ListedNoteResponse> notes) {
    }

    public record AuditEventResponse(String actor, String action, java.util.Map<String, Object> payload, Instant occurredAt) {
        static AuditEventResponse from(de.raindancer118.stoneintelligence.platform.audit.AuditEvent event) {
            return new AuditEventResponse(event.actor(), event.action(), event.payload(), event.occurredAt());
        }
    }
}
