package de.tstieh.stoneintelligence.worker.ingest;

import java.util.List;
import java.util.function.Supplier;
import de.tstieh.stoneintelligence.stoneai.extract.LlmClient;
import de.tstieh.stoneintelligence.worker.ask.AskAnswerer;
import de.tstieh.stoneintelligence.worker.embed.Embedder;
import de.tstieh.stoneintelligence.worker.platform.ClaimedJob;
import de.tstieh.stoneintelligence.worker.platform.PlatformApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eine Frage an den Vault (ADR 0017): die Frage lokal einbetten, platform-api die Stellen suchen lassen (Rechte,
 * Einwilligung und Budget dort), daraus mit dem KI-Dienst eine belegte Antwort schreiben. Ohne Stellen wird das
 * Modell gar nicht gefragt.
 */
final class AskRun {

    private static final Logger LOG = LoggerFactory.getLogger(AskRun.class);

    private final PlatformApi platform;
    private final Embedder embedder;
    private final Supplier<LlmClient> llm;

    /** @param embedder {@code null}: kein Embedding-Modell, es wird nur im Volltext gesucht */
    AskRun(PlatformApi platform, Embedder embedder, Supplier<LlmClient> llm) {
        this.platform = platform;
        this.embedder = embedder;
        this.llm = llm;
    }

    void run(ClaimedJob job) {
        platform.progress(job.jobId(), "Frage wird gelesen", 10);
        var question = platform.question(job.jobId());
        var vector = vectorOf(job, question);
        platform.progress(job.jobId(), "Passende Stellen werden gesucht", 30);
        var found = platform.passages(job.jobId(), vector);
        if (found.passages().isEmpty()) {
            platform.answer(job.jobId(), false, null);
            finish(job, "Keine passenden Stellen gefunden");
            return;
        }
        var client = llm.get();
        if (client == null) {
            platform.fail(job.jobId(), "Für den KI-Dienst ist kein Sprachmodell eingerichtet", false);
            return;
        }
        platform.progress(job.jobId(), "Antwort wird verfasst", 60);
        var answer = new AskAnswerer(client).answer(question, found.passages());
        if (answer.isEmpty()) {
            // Eine unbelegte Antwort wird nie weitergegeben; ein neuer Versuch fragt das Modell neu.
            platform.fail(job.jobId(), "Die Antwort des Modells war nicht mit Quellen belegt", true);
            return;
        }
        platform.answer(job.jobId(), answer.get().answerable(), answer.get().text());
        finish(job, answer.get().answerable() ? "Antwort fertig" : "Die Notizen beantworten die Frage nicht");
    }

    private float[] vectorOf(ClaimedJob job, String question) {
        if (embedder == null) {
            return null;
        }
        try {
            return embedder.embed(List.of(question), Embedder.Kind.QUERY)[0];
        } catch (RuntimeException broken) {
            LOG.warn("Job {}: Frage nicht einbettbar, es wird nur im Volltext gesucht: {}", job.jobId(), broken.getMessage());
            return null;
        }
    }

    private void finish(ClaimedJob job, String message) {
        platform.progress(job.jobId(), message, 100);
        platform.complete(job.jobId());
    }
}
