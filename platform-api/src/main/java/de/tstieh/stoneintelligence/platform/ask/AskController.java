package de.tstieh.stoneintelligence.platform.ask;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.ai.AiJob;
import de.tstieh.stoneintelligence.platform.ai.AiWriteRefusedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** "Frag den Vault" (ADR 0017): Frage stellen, Antwort mit Quellen lesen, eigenen Verlauf verwalten. */
@RestController
public class AskController {

    private final AskService ask;

    public AskController(AskService ask) {
        this.ask = ask;
    }

    @PostMapping("/api/v1/vaults/{vaultId}/ask")
    public QuestionResponse ask(@PathVariable String vaultId, @RequestBody AskRequest request, Authentication auth) {
        return QuestionResponse.from(ask.ask(VaultId.of(vaultId), auth.getName(), request.question(), request.service()));
    }

    /** Die eigenen Fragen, neueste zuerst. */
    @GetMapping("/api/v1/vaults/{vaultId}/ask")
    public List<QuestionResponse> history(@PathVariable String vaultId, Authentication auth) {
        return ask.list(VaultId.of(vaultId), auth.getName()).stream().map(QuestionResponse::from).toList();
    }

    @GetMapping("/api/v1/vaults/{vaultId}/ask/{questionId}")
    public QuestionResponse get(@PathVariable String vaultId, @PathVariable UUID questionId, Authentication auth) {
        return QuestionResponse.from(ask.get(VaultId.of(vaultId), auth.getName(), questionId));
    }

    @PostMapping("/api/v1/vaults/{vaultId}/ask/{questionId}/cancel")
    public QuestionResponse cancel(@PathVariable String vaultId, @PathVariable UUID questionId, Authentication auth) {
        return QuestionResponse.from(ask.cancel(VaultId.of(vaultId), auth.getName(), questionId));
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/ask/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String vaultId, @PathVariable UUID questionId, Authentication auth) {
        ask.delete(VaultId.of(vaultId), auth.getName(), questionId);
    }

    /** Die Texte sind fuer Menschen geschrieben und verraten nichts Internes - direkt anzeigen lassen. */
    @ExceptionHandler(AiWriteRefusedException.class)
    public ProblemDetail refused(AiWriteRefusedException refused) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, refused.getMessage());
    }

    public record AskRequest(String question, String service) { }

    public record CitationResponse(int n, String noteId, String path, String heading, String excerpt) { }

    /**
     * @param status {@code PENDING}, {@code RUNNING}, {@code ANSWERED}, {@code NOT_ANSWERABLE} (die lesbaren Notizen
     *               beantworten es nicht), {@code FAILED}, {@code CANCELLED}
     * @param restricted eine zitierte Notiz ist nicht mehr lesbar - die Antwort ist verborgen
     * @param omittedForConsent Notizen mit passenden Stellen, die ein externer Dienst nicht bekam, weil ihre Verfasser nicht eingewilligt haben
     */
    public record QuestionResponse(UUID id, String question, String service, String status, String progress, Integer percent,
                                   String error, boolean waitingForCapacity, Instant availableAt, Instant askedAt, Instant answeredAt,
                                   String answer, List<CitationResponse> citations, boolean restricted, int omittedForConsent) {

        static QuestionResponse from(AskService.View view) {
            var question = view.question();
            var job = view.job();
            var open = job != null && job.open() && question.answered() == null;
            return new QuestionResponse(question.id(), question.question(), question.service(), view.status(),
                open ? job.progress() : null, open ? job.percent() : null, job != null && "FAILED".equals(view.status()) ? job.error() : null,
                open && job.waitingForCapacity(), open && job.status() == AiJob.Status.PENDING ? job.availableAt() : null,
                question.askedAt(), question.answeredAt(), question.answer(),
                view.citations().stream().map(c -> new CitationResponse(c.n(), c.noteId().value().toString(), c.path(), c.heading(), c.excerpt())).toList(),
                view.restricted(), question.omittedForConsent());
        }
    }
}
