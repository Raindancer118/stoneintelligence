package de.raindancer118.stoneintelligence.worker.ingest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import de.raindancer118.stoneai.config.ConfigSchema;
import de.raindancer118.stoneai.extract.LlmClient;
import de.raindancer118.stoneai.pipeline.IngestPipeline;
import de.raindancer118.stoneai.pipeline.IngestReport;
import de.raindancer118.stoneai.vault.NoteStore;

/**
 * Probelauf ohne platform-api: liest Dokumente mit den Modellen eines KI-Dienstes und schreibt die
 * Notizen in einen lokalen Ordner - um die Qualitaet zu pruefen, bevor etwas in einem echten
 * Vault landet. Aufruf im Worker-Container:
 * {@code java -cp /app/app.jar -Dloader.main=de.raindancer118.stoneintelligence.worker.ingest.TrialRun
 * org.springframework.boot.loader.launch.PropertiesLauncher <dienst> <zielordner> <dokument>...}
 */
public final class TrialRun {

    private TrialRun() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Aufruf: TrialRun <dienst> <zielordner> <dokument>...");
            System.exit(2);
        }
        var model = ServiceModels.from(System.getenv()).forService(args[0]);
        var documents = new ArrayList<Path>();
        for (var i = 2; i < args.length; i++) {
            documents.add(Path.of(args[i]));
        }
        for (var report : run(documents, Path.of(args[1]), model, new GatewayLlmFactory().forService(model))) {
            System.out.printf("%s: %d Notizen, %d Tokens%s%n", report.document().getFileName(), report.notesWritten(),
                report.tokensUsed(), report.wasSkipped() ? " - uebersprungen: " + report.skippedReason() : "");
            report.failures().forEach(failure -> System.out.println("  Fehler: " + failure));
        }
    }

    static List<IngestReport> run(List<Path> documents, Path vault, ServiceModels.ServiceModel model, LlmClient llm)
            throws IOException {
        var config = GatewayLlmFactory.configFor(model);
        ConfigSchema.byPath("vault.path").set(config, vault.toAbsolutePath().toString());
        var pipeline = IngestPipeline.hosted(config, llm, LocalDate::now);
        var reports = new ArrayList<IngestReport>();
        for (var document : documents) {
            reports.add(pipeline.ingestInto(document, NoteStore.files()));
        }
        return reports;
    }
}
