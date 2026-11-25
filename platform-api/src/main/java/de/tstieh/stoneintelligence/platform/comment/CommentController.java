package de.tstieh.stoneintelligence.platform.comment;

import java.util.List;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kommentare je Notiz und Hinweise auf Erwähnungen (ADR 0016). Wer eine Notiz lesen darf, sieht und
 * schreibt ihre Kommentare; Hinweise sind persönlich und über alle Vaults der Person.
 */
@RestController
public class CommentController {

    private final CommentService service;

    public CommentController(CommentService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/comments")
    public List<CommentService.ThreadView> list(@PathVariable String vaultId, @PathVariable String noteId, Authentication auth) {
        return service.list(VaultId.of(vaultId), NoteId.of(noteId), auth.getName());
    }

    @PostMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentService.ThreadView startThread(@PathVariable String vaultId, @PathVariable String noteId,
                                                 @RequestBody NewThread request, Authentication auth) {
        return service.startThread(VaultId.of(vaultId), NoteId.of(noteId), auth.getName(), request.body(), request.anchor());
    }

    @PostMapping("/api/v1/vaults/{vaultId}/comment-threads/{threadId}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentService.ThreadView reply(@PathVariable String vaultId, @PathVariable UUID threadId,
                                           @RequestBody CommentBody request, Authentication auth) {
        return service.reply(VaultId.of(vaultId), threadId, auth.getName(), request.body());
    }

    @PostMapping("/api/v1/vaults/{vaultId}/comment-threads/{threadId}/resolve")
    public CommentService.ThreadView resolve(@PathVariable String vaultId, @PathVariable UUID threadId, Authentication auth) {
        return service.resolve(VaultId.of(vaultId), threadId, auth.getName());
    }

    @PostMapping("/api/v1/vaults/{vaultId}/comment-threads/{threadId}/reopen")
    public CommentService.ThreadView reopen(@PathVariable String vaultId, @PathVariable UUID threadId, Authentication auth) {
        return service.reopen(VaultId.of(vaultId), threadId, auth.getName());
    }

    @PatchMapping("/api/v1/vaults/{vaultId}/comments/{commentId}")
    public CommentService.ThreadView edit(@PathVariable String vaultId, @PathVariable UUID commentId,
                                          @RequestBody CommentBody request, Authentication auth) {
        return service.edit(VaultId.of(vaultId), commentId, auth.getName(), request.body());
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String vaultId, @PathVariable UUID commentId, Authentication auth) {
        service.delete(VaultId.of(vaultId), commentId, auth.getName());
    }

    @GetMapping("/api/v1/notifications")
    public CommentService.NotificationList notifications(Authentication auth) {
        return service.notifications(auth.getName());
    }

    /** Ohne {@code ids} (oder leer): alle als gelesen markieren. */
    @PostMapping("/api/v1/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@RequestBody(required = false) ReadRequest request, Authentication auth) {
        service.markRead(auth.getName(), request == null || request.ids() == null ? List.of() : request.ids());
    }

    /** Die Texte sind für Menschen geschrieben und verraten nichts Internes. */
    @ExceptionHandler(CommentRejectedException.class)
    public ProblemDetail rejected(CommentRejectedException rejected) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, rejected.getMessage());
    }

    public record NewThread(String body, Anchor anchor) {
    }

    public record CommentBody(String body) {
    }

    public record ReadRequest(List<UUID> ids) {
    }
}
