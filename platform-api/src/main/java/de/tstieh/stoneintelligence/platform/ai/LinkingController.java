package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Verlinkung eines Vaults (ADR 0012): Einstellungen ansehen/aendern und "Jetzt verlinken". */
@RestController
public class LinkingController {

    private final LinkingService linking;

    public LinkingController(LinkingService linking) {
        this.linking = linking;
    }

    @GetMapping("/api/v1/vaults/{vaultId}/linking")
    public LinkingResponse settings(@PathVariable String vaultId, Authentication auth) {
        return LinkingResponse.from(linking.settings(VaultId.of(vaultId), auth.getName()));
    }

    @PutMapping("/api/v1/vaults/{vaultId}/linking")
    public LinkingResponse update(@PathVariable String vaultId, @RequestBody LinkingService.Change change, Authentication auth) {
        return LinkingResponse.from(linking.update(VaultId.of(vaultId), auth.getName(), change));
    }

    @PostMapping("/api/v1/vaults/{vaultId}/linking/run")
    public AiController.JobResponse runNow(@PathVariable String vaultId, Authentication auth) {
        return AiController.JobResponse.from(linking.runNow(VaultId.of(vaultId), auth.getName()));
    }

    /** Fachliche Ablehnungen mit lesbarem Grund (422), wie im uebrigen KI-Bereich. */
    @ExceptionHandler(AiWriteRefusedException.class)
    public org.springframework.http.ProblemDetail refused(AiWriteRefusedException refused) {
        return org.springframework.http.ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
            refused.getMessage());
    }

    /** "Aehnliche Notizen" (ADR 0012): nach Bedeutung, nur unter denen, die man lesen darf. */
    @GetMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/similar")
    public java.util.List<SimilarResponse> similar(@PathVariable String vaultId, @PathVariable String noteId,
                                                   @org.springframework.web.bind.annotation.RequestParam(defaultValue = "10") int limit,
                                                   Authentication auth) {
        return linking.similarNotes(VaultId.of(vaultId), auth.getName(), de.tstieh.stoneintelligence.domain.id.NoteId.of(noteId),
                Math.min(Math.max(limit, 1), 50)).stream()
            .map(note -> new SimilarResponse(note.noteId().value().toString(), note.path(), note.heading(), note.similarity()))
            .toList();
    }

    public record SimilarResponse(String noteId, String path, String heading, double similarity) {
    }

    public record LinkingResponse(boolean enabled, String mode, boolean linkHumanNotes, Integer maxLinksPerNote, String service,
                                  String requestedBy, Instant lastRunAt) {
        static LinkingResponse from(LinkingSettings settings) {
            return new LinkingResponse(settings.enabled(), settings.mode().name(), settings.linkHumanNotes(), settings.maxLinksPerNote(),
                settings.service(), settings.requestedBy(), settings.lastRunAt());
        }
    }
}
