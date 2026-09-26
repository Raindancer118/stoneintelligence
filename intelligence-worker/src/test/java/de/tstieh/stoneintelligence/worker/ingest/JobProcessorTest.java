package de.tstieh.stoneintelligence.worker.ingest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import de.tstieh.stoneintelligence.stoneai.extract.LlmAnswer;
import de.tstieh.stoneintelligence.stoneai.extract.LlmClient;
import de.tstieh.stoneintelligence.stoneai.extract.Tier;
import de.tstieh.stoneintelligence.worker.platform.ClaimedJob;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobProcessorTest {

    private static final String ANSWER = """
        {"concepts":[{"title":"Photosynthese","aliases":[],"definition":"Licht wird zu Zucker.",
          "body":"Findet in Chloroplasten statt.","tags":["biologie"],"entities":{},"related":["Zelle"],"confidence":0.9}]}
        """;

    private static final String PLAN = """
        {"topics":[{"title":"Photosynthese","kind":"begriff","scope":"Ablauf"}]}
        """;

    private final FakePlatform platform = new FakePlatform();

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path resumeRoot;

    private static LlmClient answering(String answer) {
        return new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                return new LlmAnswer(system.contains("Themenplan") ? PLAN : answer, 5, "fake/model");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                return new LlmAnswer("", 0, "fake/vision");
            }
        };
    }

    private static LlmClient failing() {
        return new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                throw new IllegalStateException("kein Provider konnte antworten: 429");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                throw new IllegalStateException("kein Provider konnte antworten");
            }
        };
    }

    private ClaimedJob job(String fileName, String content) {
        var job = new ClaimedJob(UUID.randomUUID(), "vault-1", "gemini", "tom", fileName, "text/markdown",
            content.length(), 1, UUID.randomUUID(), 1);
        platform.documents.put(job.jobId(), content.getBytes(StandardCharsets.UTF_8));
        return job;
    }

    private JobProcessor processor(LlmClient llm) {
        return processor(service -> llm);
    }

    private JobProcessor processor(LlmFactory llms) {
        return new JobProcessor(platform, llms, ServiceModels.from(java.util.Map.of()), () -> LocalDate.of(2026, 9, 23),
            java.time.Duration.ZERO, resumeRoot);
    }

    private static final java.time.Instant BACK = java.time.Instant.parse("2026-09-26T07:00:00Z");

    private static LlmClient outOfCapacity() {
        return new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                if (system.contains("Themenplan")) {
                    return new LlmAnswer(PLAN, 5, "fake/model");
                }
                throw new de.tstieh.stoneintelligence.stoneai.extract.LlmCapacityException("kein Kontingent frei: groq 429", BACK);
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void should_turnADocumentIntoNotes_inTheSharedVault_andReportDone() {
        platform.human("Bio/Zelle.md", "# Zelle\n", 1);

        processor(answering(ANSWER)).process(job("Skript.md", "# Photosynthese\n\nLicht und Wasser.\n"));

        assertThat(platform.events).contains("create Notizen/Photosynthese.md", "create Quellen/Skript.md", "complete");
        var note = platform.notes.values().stream().filter(n -> n.path().equals("Notizen/Photosynthese.md")).findFirst().orElseThrow();
        assertThat(note.text()).contains("Licht wird zu Zucker.").contains("[[Zelle]]");
        assertThat(platform.events.getFirst()).startsWith("progress");
    }

    @Test
    void should_keepTheOriginalPdf_inTheVault_andLinkItFromTheSourceNote() throws Exception {
        var pdf = new java.io.ByteArrayOutputStream();
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(60, 700);
                content.showText("Photosynthese braucht Licht.");
                content.endText();
            }
            document.save(pdf);
        }
        var job = new ClaimedJob(UUID.randomUUID(), "vault-1", "gemini", "tom", "Skript.pdf", "application/pdf",
            pdf.size(), 1, UUID.randomUUID(), 1);
        platform.documents.put(job.jobId(), pdf.toByteArray());

        processor(answering(ANSWER)).process(job);

        assertThat(platform.files).containsKey("Anhänge/Skript.pdf");
        var source = platform.notes.values().stream().filter(n -> n.path().startsWith("Quellen/")).findFirst().orElseThrow();
        assertThat(source.text()).contains("![[Skript.pdf]]");
        assertThat(platform.events).last().isEqualTo("complete");
    }

    // Ein Lauf, der nicht alles lesen konnte, meldet sich fertig - aber sagt, was fehlt.
    @Test
    void should_sayWhatWasLeftUnread_whenTheBudgetRanOut() {
        var llm = new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                return new LlmAnswer(system.contains("Themenplan") ? PLAN : ANSWER, 500_000, "fake/model");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                return new LlmAnswer("", 0, "fake/vision");
            }
        };
        var section = "Licht ".repeat(2_000);

        var skript = new StringBuilder();
        for (int part = 1; part <= 6; part++) {
            skript.append("# Teil ").append(part).append("\n\n").append(section).append("\n\n");
        }

        processor(llm).process(job("Skript.md", skript.toString()));

        // Wie viele Abschnitte vor dem Ende des Budgets noch starten, haengt von der Parallelitaet
        // (llm.parallelCalls) und damit vom Rechner ab - fest steht nur, dass die ungelesenen genannt werden.
        assertThat(platform.events).anySatisfy(event -> assertThat(event).startsWith("progress 100").contains("nicht verarbeitet")
            .containsPattern("Skript — Teil \\d"));
        assertThat(platform.events).last().isEqualTo("complete");
    }

    // Ein als privat markiertes Dokument erreicht nie einen Anbieter - und ein erneuter Versuch aendert daran nichts.
    @Test
    void should_failPermanently_whenTheDocumentIsProtected() {
        processor(answering(ANSWER)).process(job("Steuer [noai].md", "# Geheim\n\nInhalt.\n"));

        assertThat(platform.events).last().asString().startsWith("fail ").contains("noai");
        assertThat(platform.notes).isEmpty();
    }

    @Test
    void should_retryLater_whenNoModelCouldAnswer() {
        processor(failing()).process(job("Skript.md", "# Photosynthese\n\nText.\n"));

        assertThat(platform.events).last().asString().startsWith("retry ");
    }

    @Test
    void should_retryLater_whenTheDocumentIsGone() {
        var job = job("Skript.md", "x");
        platform.documents.remove(job.jobId());

        processor(answering(ANSWER)).process(job);

        assertThat(platform.events).last().asString().startsWith("retry ");
    }

    // Leeres Kontingent: der Lauf schreibt nichts und wartet, statt als Fehlversuch zu zaehlen oder
    // "fertig" mit Luecken zu melden.
    @Test
    void should_waitForCapacity_andWriteNothing_whenTheModelsRanOut() {
        processor(outOfCapacity()).process(job("Skript.md", "# Photosynthese\n\nLicht und Wasser.\n"));

        assertThat(platform.events).last().asString().startsWith("wait " + BACK + " ");
        assertThat(platform.events).noneMatch(event -> event.startsWith("create") || event.startsWith("file")
            || event.equals("complete") || event.startsWith("retry") || event.startsWith("fail"));
        assertThat(platform.notes).isEmpty();
    }

    @Test
    void should_notEvenStart_whenEveryProviderOfTheServiceIsKnownToBeExhausted() {
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        var llms = new LlmFactory() {
            @Override
            public LlmClient forService(ServiceModels.ServiceModel model) {
                called.set(true);
                return answering(ANSWER);
            }

            @Override
            public java.util.Map<String, io.github.raindancer118.aigateway.ProviderCapacity> capacity(ServiceModels.ServiceModel model) {
                return java.util.Map.of("groq", new io.github.raindancer118.aigateway.ProviderCapacity("groq", 1, 0, true, BACK,
                    new io.github.raindancer118.aigateway.ProviderCapacity.Quota(0, 1000, BACK), null, null, BACK.minusSeconds(60)));
            }
        };

        processor(llms).process(job("Skript.md", "# Photosynthese\n\nText.\n"));

        assertThat(called).isFalse();
        assertThat(platform.events).singleElement().asString().startsWith("wait " + BACK + " ").contains("groq");
    }

    // Abgebrochen, waehrend er lief: der Worker hoert auf, schreibt nichts mehr und meldet nichts.
    @Test
    void should_stopQuietly_whenTheJobWasCancelledWhileRunning() {
        platform.cancelAfterProgress = 2;

        processor(answering(ANSWER)).process(job("Skript.md", "# Photosynthese\n\nLicht und Wasser.\n"));

        assertThat(platform.events).allMatch(event -> event.startsWith("progress"));
        assertThat(platform.notes).isEmpty();
    }

    @Test
    void should_stopQuietly_whenTheJobWasCancelledBeforeItStarted() {
        platform.cancelAfterProgress = 0;

        processor(answering(ANSWER)).process(job("Skript.md", "# Photosynthese\n\nText.\n"));

        assertThat(platform.events).isEmpty();
    }

    // Ein 800-Seiten-Buch, dem nach der Haelfte das Kontingent ausgeht: der naechste Versuch fragt
    // nur, was noch fehlt - sonst verbraucht er das neue Kontingent wieder fuer die erste Haelfte.
    @Test
    void should_goOnWhereItStopped_whenTheQuotaRanOutHalfway() {
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        var quotaLeft = new java.util.concurrent.atomic.AtomicInteger(4);
        var llm = new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                if (quotaLeft.getAndDecrement() <= 0) {
                    throw new de.tstieh.stoneintelligence.stoneai.extract.LlmCapacityException("kein Kontingent frei: gemini 429", BACK);
                }
                asked.incrementAndGet();
                return new LlmAnswer(system.contains("Themenplan") ? PLAN : ANSWER, 5, "fake/model");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                return new LlmAnswer("", 0, "fake/vision");
            }
        };
        var section = "Licht ".repeat(2_000);
        var skript = new StringBuilder();
        for (int part = 1; part <= 8; part++) {
            skript.append("# Teil ").append(part).append("\n\n").append(section).append(" ").append(part).append("\n\n");
        }
        var job = job("Buch.md", skript.toString());

        processor(llm).process(job);
        assertThat(platform.events).last().asString().startsWith("wait ");
        int firstAttempt = asked.get();

        quotaLeft.set(1_000);
        platform.events.clear();
        processor(llm).process(job);

        assertThat(platform.events).last().isEqualTo("complete");
        // Together the two attempts asked exactly what one uninterrupted run asks - nothing twice.
        var uninterrupted = new FakePlatform();
        var fresh = new ClaimedJob(UUID.randomUUID(), "vault-1", "gemini", "tom", "Buch.md", "text/markdown",
            skript.length(), 1, UUID.randomUUID(), 1);
        uninterrupted.documents.put(fresh.jobId(), skript.toString().getBytes(StandardCharsets.UTF_8));
        int before = asked.get();
        new JobProcessor(uninterrupted, service -> llm, ServiceModels.from(java.util.Map.of()), () -> LocalDate.of(2026, 9, 23),
            java.time.Duration.ZERO, resumeRoot.resolve("other")).process(fresh);
        int oneRun = asked.get() - before;
        assertThat(firstAttempt).isGreaterThan(0);
        assertThat(before).isEqualTo(oneRun);
    }

    @Test
    void should_forgetTheAnswers_onceTheJobIsDone() throws Exception {
        processor(answering(ANSWER)).process(job("Skript.md", "# Photosynthese\n\nLicht und Wasser.\n"));

        assertThat(platform.events).last().isEqualTo("complete");
        try (var left = java.nio.file.Files.list(resumeRoot)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void should_keepTheAnswers_whileTheJobWaitsForCapacity() throws Exception {
        var job = job("Skript.md", "# Photosynthese\n\nLicht und Wasser.\n");

        processor(outOfCapacity()).process(job);

        assertThat(resumeRoot.resolve(job.jobId().toString())).isDirectory();
    }

    @Test
    void should_forgetTheAnswers_whenTheJobWasCancelled() throws Exception {
        platform.cancelAfterProgress = 2;

        processor(answering(ANSWER)).process(job("Skript.md", "# Photosynthese\n\nLicht und Wasser.\n"));

        try (var left = java.nio.file.Files.list(resumeRoot)) {
            assertThat(left).isEmpty();
        }
    }

    // ADR 0012: ein Verlinkungslauf braucht weder Dokument noch Sprachmodell.
    @Test
    void should_runALinkingJob_withoutDocumentOrModel() {
        platform.human("Licht.md", "# Licht\n", 1);
        platform.human("Pflanzen.md", "Pflanzen brauchen Licht.\n", 1);
        var job = new ClaimedJob(UUID.randomUUID(), "vault-1", "gemini", "tom", "Verlinkung", "text/plain", 0, 1, UUID.randomUUID(), 1,
            "LINKING");

        processor(answering("sollte nie gefragt werden")).process(job);

        assertThat(platform.events).contains("link Pflanzen.md 1");
        assertThat(platform.events).last().isEqualTo("complete");
    }
}
