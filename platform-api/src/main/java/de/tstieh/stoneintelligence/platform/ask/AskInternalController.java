package de.tstieh.stoneintelligence.platform.ask;

import java.util.List;
import java.util.UUID;
import de.tstieh.stoneintelligence.platform.ai.AiJobGoneException;
import de.tstieh.stoneintelligence.platform.ai.AiWriteRefusedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Der Weg des KI-Workers fuer ASK-Jobs (ADR 0017), nur mit Worker-Token erreichbar. */
@RestController
@RequestMapping("/internal/ai/ask/jobs/{jobId}")
public class AskInternalController {

    private final AskService ask;

    public AskInternalController(AskService ask) {
        this.ask = ask;
    }

    @GetMapping("/question")
    public QuestionText question(@PathVariable UUID jobId) {
        return new QuestionText(ask.questionText(jobId));
    }

    /** Die Stellen zur Frage; {@code vector} ist der Vektor der Frage (384 Werte) oder leer fuer nur Volltext. */
    @PostMapping("/passages")
    public PassagesResponse passages(@PathVariable UUID jobId, @RequestBody PassagesRequest request) {
        var found = ask.passages(jobId, request.vector());
        return new PassagesResponse(found.passages().stream()
            .map(p -> new PassageResponse(p.n(), p.noteId().value().toString(), p.path(), p.title(), p.heading(), p.text())).toList(),
            found.local(), found.omittedForConsent());
    }

    @PostMapping("/answer")
    public QuestionText answer(@PathVariable UUID jobId, @RequestBody AnswerRequest request) {
        ask.answer(jobId, request.answerable(), request.text());
        return new QuestionText(null);
    }

    @ExceptionHandler(AiJobGoneException.class)
    public ProblemDetail gone(AiJobGoneException gone) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.GONE, gone.getMessage());
    }

    @ExceptionHandler(AiWriteRefusedException.class)
    public ProblemDetail refused(AiWriteRefusedException refused) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, refused.getMessage());
    }

    public record QuestionText(String question) { }

    public record PassagesRequest(float[] vector) { }

    public record PassageResponse(int n, String noteId, String path, String title, String heading, String text) { }

    public record PassagesResponse(List<PassageResponse> passages, boolean local, int omittedForConsent) { }

    public record AnswerRequest(boolean answerable, String text) { }
}
