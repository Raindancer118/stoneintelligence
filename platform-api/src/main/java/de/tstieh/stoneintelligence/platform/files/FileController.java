package de.tstieh.stoneintelligence.platform.files;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.NoteController.NoteResponse;
import de.tstieh.stoneintelligence.platform.vault.NoteNotFoundException;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dateien im Vault (ADR 0009). Umbenennen und Loeschen laufen ueber die Notiz-Endpunkte
 * ({@code PATCH|DELETE /notes/{id}}), damit beide Arten dieselben Tombstones und Ankuendigungen haben.
 */
@RestController
@RequestMapping("/api/v1/vaults/{vaultId}/files")
public class FileController {

    private final FileService files;
    private final NoteRepository notes;
    private final VaultAccessGuard access;

    public FileController(FileService files, NoteRepository notes, VaultAccessGuard access) {
        this.files = files;
        this.notes = notes;
        this.access = access;
    }

    @PostMapping
    @Transactional
    public NoteResponse create(@PathVariable String vaultId, @RequestBody CreateFileRequest request, Authentication auth) {
        var vId = VaultId.of(vaultId);
        if (!FilePaths.isValid(request.path())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file path");
        }
        access.require(vId, auth.getName(), Permission.CREATE, request.path());
        return NoteResponse.from(files.create(vId, request.path(), NoteLevel.of(request.level() == null ? 1 : request.level()),
            auth.getName()));
    }

    /** Neue Fassung; {@code If-Match: "<revision>"} nennt die Fassung, auf der die Aenderung beruht ("0" = erste). */
    @PutMapping("/{noteId}/content")
    public VersionResponse upload(@PathVariable String vaultId, @PathVariable String noteId,
                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                  HttpServletRequest request, Authentication auth) throws IOException {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        var expected = revisionOf(ifMatch);
        access.require(vId, auth.getName(), Permission.WRITE, pathOf(vId, nId, auth.getName()));
        return VersionResponse.from(files.upload(vId, nId, expected, request.getInputStream(), request.getContentType(), auth.getName()));
    }

    /**
     * Immer als Anhang, mit {@code nosniff} und einer Sandbox-CSP: eine hochgeladene SVG- oder
     * HTML-Datei fuehrt so nie Skripte im Kontext der API aus (ADR 0009 Punkt 7).
     */
    @GetMapping("/{noteId}/content")
    public ResponseEntity<InputStreamResource> download(@PathVariable String vaultId, @PathVariable String noteId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        var nId = NoteId.of(noteId);
        access.require(vId, auth.getName(), Permission.READ, pathOf(vId, nId, auth.getName()));
        var download = files.download(vId, nId);
        var name = download.note().path().substring(download.note().path().lastIndexOf('/') + 1);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(download.version().contentType()))
            .contentLength(download.version().size())
            .eTag("\"" + download.version().revision() + "\"")
            .header("X-Content-SHA256", download.version().sha256())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"))
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "sandbox; default-src 'none'")
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(new InputStreamResource(download.content()));
    }

    /** Erst Mitgliedschaft, dann der Pfad - wer nicht im Vault ist, erfaehrt nichts ueber dessen Eintraege. */
    private String pathOf(VaultId vaultId, NoteId noteId, String actor) {
        access.requireMember(vaultId, actor);
        return notes.findById(vaultId, noteId).orElseThrow(() -> new NoteNotFoundException(vaultId, noteId)).path();
    }

    private static long revisionOf(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match with the base revision is required");
        }
        try {
            var revision = Long.parseLong(ifMatch.strip().replaceFirst("^W/", "").replace("\"", ""));
            if (revision < 0) {
                throw new NumberFormatException();
            }
            return revision;
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must name a revision");
        }
    }

    @ExceptionHandler(FileRevisionConflictException.class)
    public ResponseEntity<ProblemDetail> conflict(FileRevisionConflictException conflict) {
        return ResponseEntity.status(HttpStatus.CONFLICT).header("X-Current-Revision", String.valueOf(conflict.currentRevision()))
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, conflict.getMessage()));
    }

    @ExceptionHandler(BlobTooLargeException.class)
    public ProblemDetail tooLarge(BlobTooLargeException tooLarge) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, tooLarge.getMessage());
    }

    @ExceptionHandler(FileQuotaExceededException.class)
    public ProblemDetail quota(FileQuotaExceededException quota) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INSUFFICIENT_STORAGE, quota.getMessage());
    }

    @ExceptionHandler(FileRefusedException.class)
    public ProblemDetail refused(FileRefusedException refused) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, refused.getMessage());
    }

    @ExceptionHandler(BlobStoreUnavailableException.class)
    public ProblemDetail unavailable(BlobStoreUnavailableException unavailable) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
            "Der Dateispeicher ist gerade nicht erreichbar. Bitte später erneut versuchen.");
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<Void> duplicatePath() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    public record CreateFileRequest(String path, Integer level) { }

    public record VersionResponse(String noteId, long revision, String sha256, long size, String contentType) {
        static VersionResponse from(FileVersion version) {
            return new VersionResponse(version.noteId().value().toString(), version.revision(), version.sha256(), version.size(),
                version.contentType());
        }
    }
}
