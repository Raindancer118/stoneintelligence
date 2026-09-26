package de.tstieh.stoneintelligence.platform.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tstieh.stoneintelligence.platform.security.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der KI-Schreibweg gegen die echte Anwendung (ADR 0008): der Worker schreibt ueber
 * {@code /internal/**} mit seinem Service-Token, Menschen sehen und widerrufen KI-Aenderungen
 * ueber die normale, OIDC-geschuetzte API.
 */
@Testcontainers
@Import(TestJwtSupport.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiEndToEndIT {

    private static final java.nio.file.Path STORAGE = temporaryStorage();

    private static java.nio.file.Path temporaryStorage() {
        try {
            return java.nio.file.Files.createTempDirectory("si-ai-files-");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static final String WORKER_TOKEN = "e2e-worker-token-0123456789abcdef-0123456789";
    private static final String WORKER_HEADER = "X-StoneIntelligence-Worker-Token";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("STONEINTELLIGENCE_AI_SERVICES", () -> "gemini|Gemini|1; lokal|Ollama lokal|1,2");
        registry.add("STONEINTELLIGENCE_AI_WORKER_TOKEN", () -> WORKER_TOKEN);
        registry.add("STONEINTELLIGENCE_FILE_STORAGE_DIR", () -> STORAGE.toString());
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private static Map<String, String> user(String name) {
        return Map.of("Authorization", "Bearer " + TestJwtSupport.signedJwtFor(name));
    }

    private static final Map<String, String> WORKER = Map.of(WORKER_HEADER, WORKER_TOKEN);

    private HttpResponse<String> send(String method, String path, Map<String, String> headers, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        headers.forEach(builder::header);
        if (body != null) {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendBytes(String path, byte[] body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/pdf").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        WORKER.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readValue(response.body(), new TypeReference<>() { });
    }

    private List<Map<String, Object>> okList(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readValue(response.body(), new TypeReference<>() { });
    }

    @Test
    void should_letTheWorkerWriteForAPerson_andThePersonUndoIt() throws Exception {
        var tom = user("ai-tom");
        var vault = ok(send("POST", "/api/v1/vaults", tom, Map.of("name", "KI")));
        var base = "/api/v1/vaults/" + vault.get("id");
        var internal = "/internal/ai/vaults/" + vault.get("id");

        // Welche Dienste es gibt und was sie duerfen, sieht jede angemeldete Person.
        assertThat(okList(send("GET", "/api/v1/ai/services", tom, null)))
            .containsExactly(Map.of("id", "gemini", "name", "Gemini", "levels", List.of(1)),
                Map.of("id", "lokal", "name", "Ollama lokal", "levels", List.of(1, 2)));

        // Nur der Worker kommt an /internal - weder ohne, mit falschem Token noch mit einem Personen-Token.
        assertThat(send("GET", "/internal/ai/services", Map.of(), null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/internal/ai/services", Map.of(WORKER_HEADER, WORKER_TOKEN + "x"), null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/internal/ai/services", tom, null).statusCode()).isEqualTo(401);
        assertThat(okList(send("GET", "/internal/ai/services", WORKER, null))).hasSize(2);

        // Die KI handelt hoechstens mit den Rechten der Person, die sie beauftragt hat.
        assertThat(send("POST", internal + "/change-sets", WORKER,
            Map.of("service", "gemini", "requestedBy", "fremd", "label", "x.pdf")).statusCode()).isEqualTo(403);
        var changeSet = ok(send("POST", internal + "/change-sets", WORKER,
            Map.of("service", "gemini", "requestedBy", "ai-tom", "label", "Vorlesung.pdf")));
        var notes = internal + "/change-sets/" + changeSet.get("id") + "/notes";

        var written = ok(send("POST", notes, WORKER, Map.of("path", "Wissen/Photosynthese.md", "text", "# Photosynthese\n", "level", 1)));
        assertThat(send("POST", notes, WORKER, Map.of("path", "Intern.md", "text", "x", "level", 2)).statusCode()).isEqualTo(422);
        assertThat(ok(send("PUT", notes + "/" + written.get("noteId"), WORKER, Map.of("text", "# Photosynthese\n\nLicht.\n"))))
            .containsEntry("noteId", written.get("noteId"));
        assertThat(ok(send("GET", notes + "/" + written.get("noteId"), WORKER, null))).containsEntry("text", "# Photosynthese\n\nLicht.\n");

        var note = ok(send("GET", base + "/notes/" + written.get("noteId"), tom, null));
        assertThat(note).containsEntry("path", "Wissen/Photosynthese.md");

        // Das gelesene Original landet als synchronisierte Datei im Vault.
        var original = ok(sendBytes(internal + "/change-sets/" + changeSet.get("id") + "/files?path="
            + java.net.URLEncoder.encode("Anhänge/Vorlesung.pdf", java.nio.charset.StandardCharsets.UTF_8) + "&level=1",
            "%PDF-1.7 Vorlesung".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(original).containsEntry("path", "Anhänge/Vorlesung.pdf");
        var download = send("GET", base + "/files/" + original.get("noteId") + "/content", tom, null);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo("%PDF-1.7 Vorlesung");
        var folders = send("GET", base + "/folders", tom, null);
        assertThat(json.readValue(folders.body(), List.class)).containsExactlyInAnyOrder("Anhänge", "Wissen");

        // Die Person sieht, was die KI getan hat, und macht es rueckgaengig.
        var sets = okList(send("GET", base + "/ai/change-sets", tom, null));
        assertThat(sets).singleElement().satisfies(set -> {
            assertThat(set).containsEntry("label", "Vorlesung.pdf").containsEntry("agent", "ki:Gemini").containsEntry("requestedBy", "ai-tom");
            assertThat(set.get("revertedAt")).isNull();
        });
        assertThat(send("GET", base + "/ai/change-sets", user("zaungast"), null).statusCode()).isEqualTo(403);
        var detail = ok(send("GET", base + "/ai/change-sets/" + changeSet.get("id"), tom, null));
        assertThat((List<?>) detail.get("changes")).hasSize(3);

        var report = ok(send("POST", base + "/ai/change-sets/" + changeSet.get("id") + "/revert", tom, null));
        assertThat(report).containsEntry("reverted", 3).containsEntry("conflicts", List.of());
        assertThat(send("GET", base + "/notes/" + written.get("noteId"), tom, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", base + "/files/" + original.get("noteId") + "/content", tom, null).statusCode()).isEqualTo(404);
        assertThat(send("POST", base + "/ai/change-sets/" + changeSet.get("id") + "/revert", tom, null).statusCode()).isEqualTo(422);
    }

    private HttpResponse<String> upload(String path, Map<String, String> headers, Map<String, String> fields,
                                        Map<String, byte[]> files) throws Exception {
        var boundary = "----si" + java.util.UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                + field.getValue() + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        for (var file : files.entrySet()) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + file.getKey()
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.writeBytes(file.getValue());
            body.writeBytes("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // Der ganze Weg eines Dokuments: hochladen, Worker holt es, schreibt Notizen, meldet fertig.
    @Test
    void should_queueUploadedDocuments_forTheWorker() throws Exception {
        var anna = user("ai-anna");
        var vault = ok(send("POST", "/api/v1/vaults", anna, Map.of("name", "Uploads")));
        var base = "/api/v1/vaults/" + vault.get("id");
        var pdf = "%PDF-1.7\nInhalt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ok(send("POST", base + "/notes", anna, Map.of("path", "Bestand.md", "noteLevel", 1)));

        assertThat(upload(base + "/ai/jobs", user("zaungast"), Map.of("service", "gemini", "level", "1"), Map.of("a.pdf", pdf))
            .statusCode()).isEqualTo(403);
        var refused = upload(base + "/ai/jobs", anna, Map.of("service", "gemini", "level", "2"), Map.of("a.pdf", pdf));
        assertThat(refused.statusCode()).isEqualTo(422);
        // Die Begruendung ist fuer Menschen geschrieben - das Dashboard zeigt sie direkt an.
        assertThat(json.readTree(refused.body()).path("detail").asText()).isEqualTo("Gemini darf Dokumente mit Level 2 nicht verarbeiten");
        var queued = okList(upload(base + "/ai/jobs", anna, Map.of("service", "gemini", "level", "1"),
            new java.util.LinkedHashMap<>(Map.of("Vorlesung.pdf", pdf, "Notizen.md", "# Notizen\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        assertThat(queued).extracting(job -> job.get("status")).containsOnly("PENDING");
        assertThat(queued).extracting(job -> job.get("fileName")).containsExactlyInAnyOrder("Vorlesung.pdf", "Notizen.md");
        var cancelled = queued.stream().filter(j -> j.get("fileName").equals("Notizen.md")).findFirst().orElseThrow().get("id");
        assertThat(ok(send("POST", base + "/ai/jobs/" + cancelled + "/cancel", anna, null))).containsEntry("status", "CANCELLED");

        var job = ok(send("POST", "/internal/ai/jobs/claim", WORKER, null));
        assertThat(job).containsEntry("fileName", "Vorlesung.pdf").containsEntry("requestedBy", "ai-anna")
            .containsEntry("service", "gemini").containsEntry("level", 1);
        assertThat(send("POST", "/internal/ai/jobs/claim", WORKER, null).statusCode()).isEqualTo(204);
        var document = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/ai/jobs/" + job.get("jobId") + "/document"))
            .header(WORKER_HEADER, WORKER_TOKEN).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(document.body()).isEqualTo(pdf);

        var internal = "/internal/ai/vaults/" + vault.get("id") + "/change-sets/" + job.get("changeSetId");
        assertThat(okList(send("GET", internal + "/notes", WORKER, null))).extracting(note -> note.get("path")).containsExactly("Bestand.md");
        ok(send("POST", "/internal/ai/jobs/" + job.get("jobId") + "/progress", WORKER, Map.of("message", "Seite 1 von 1", "percent", 50)));
        ok(send("POST", internal + "/notes", WORKER, Map.of("path", "Wissen/Aus der Vorlesung.md", "text", "# Wissen\n", "level", 1)));
        ok(send("POST", "/internal/ai/jobs/" + job.get("jobId") + "/complete", WORKER, null));

        var jobs = okList(send("GET", base + "/ai/jobs", anna, null));
        assertThat(jobs).filteredOn(j -> j.get("id").equals(job.get("jobId"))).singleElement().satisfies(done -> {
            assertThat(done).containsEntry("status", "SUCCEEDED").containsEntry("changeSetId", job.get("changeSetId"));
        });
        // Beendet: der Worker bekommt 410 und hoert auf.
        assertThat(send("GET", "/internal/ai/jobs/" + job.get("jobId") + "/document", WORKER, null).statusCode()).isEqualTo(410);
    }

    // Kein Kontingent: der Lauf wartet ohne verbrauchten Versuch; abbrechen geht wartend wie laufend,
    // und was ein laufender Job schon geschrieben hat, verschwindet wieder.
    @Test
    void should_waitForCapacity_andCancelWaitingAndRunningJobs() throws Exception {
        var anna = user("ai-berta");
        var vault = ok(send("POST", "/api/v1/vaults", anna, Map.of("name", "Kontingent")));
        var base = "/api/v1/vaults/" + vault.get("id");
        var pdf = "%PDF-1.7\nInhalt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        var waiting = okList(upload(base + "/ai/jobs", anna, Map.of("service", "gemini", "level", "1"), Map.of("Skript.pdf", pdf))).getFirst();
        var claimed = ok(send("POST", "/internal/ai/jobs/claim", WORKER, null));
        assertThat(claimed).containsEntry("jobId", waiting.get("id"));
        var back = java.time.Instant.now().plusSeconds(3600);
        ok(send("POST", "/internal/ai/jobs/" + claimed.get("jobId") + "/wait-for-capacity", WORKER,
            Map.of("error", "Kontingent von Gemini aufgebraucht", "availableAt", back.toString())));
        assertThat(okList(send("GET", base + "/ai/jobs", anna, null))).singleElement().satisfies(job -> {
            assertThat(job).containsEntry("status", "PENDING").containsEntry("waitingForCapacity", true)
                .containsEntry("error", "Kontingent von Gemini aufgebraucht");
            assertThat(java.time.Instant.parse((String) job.get("availableAt"))).isCloseTo(back, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
        });
        assertThat(send("POST", "/internal/ai/jobs/claim", WORKER, null).statusCode()).isEqualTo(204);
        assertThat(ok(send("POST", base + "/ai/jobs/" + waiting.get("id") + "/cancel", anna, null))).containsEntry("status", "CANCELLED");

        var running = okList(upload(base + "/ai/jobs", anna, Map.of("service", "gemini", "level", "1"), Map.of("Folien.pdf", pdf))).getFirst();
        var job = ok(send("POST", "/internal/ai/jobs/claim", WORKER, null));
        assertThat(job).containsEntry("jobId", running.get("id"));
        var internal = "/internal/ai/vaults/" + vault.get("id") + "/change-sets/" + job.get("changeSetId");
        ok(send("POST", internal + "/notes", WORKER, Map.of("path", "Wissen/Halbfertig.md", "text", "# Halb\n", "level", 1)));
        assertThat(ok(send("GET", base + "/notes", anna, null)).toString()).contains("Halbfertig");

        assertThat(ok(send("POST", base + "/ai/jobs/" + running.get("id") + "/cancel", anna, null))).containsEntry("status", "CANCELLED");

        assertThat(ok(send("GET", base + "/notes", anna, null)).toString()).doesNotContain("Halbfertig");
        assertThat(send("POST", "/internal/ai/jobs/" + job.get("jobId") + "/progress", WORKER, Map.of("message", "weiter", "percent", 60))
            .statusCode()).isEqualTo(410);
        assertThat(send("POST", internal + "/notes", WORKER, Map.of("path", "Wissen/Nachzuegler.md", "text", "# x\n", "level", 1))
            .statusCode()).isEqualTo(422);
        assertThat(send("POST", base + "/ai/jobs/" + running.get("id") + "/cancel", anna, null).statusCode()).isEqualTo(422);
    }

    @Test
    void should_showTheCapacityTheWorkerReported() throws Exception {
        var anna = user("ai-carla");
        assertThat(ok(send("GET", "/api/v1/ai/services/lokal/capacity", anna, null))).containsEntry("reportedAt", null)
            .containsEntry("exhausted", null);

        var back = java.time.Instant.now().plusSeconds(600).toString();
        var provider = new java.util.HashMap<String, Object>(Map.of("provider", "lokal", "keys", 1, "usableKeys", 0, "exhausted", true,
            "availableAgainAt", back, "tokens", Map.of("remaining", 0, "limit", 6000, "resetsAt", back)));
        assertThat(send("PUT", "/internal/ai/services/lokal/capacity", anna, Map.of("providers", List.of(provider))).statusCode())
            .isIn(401, 403);
        ok(send("PUT", "/internal/ai/services/lokal/capacity", WORKER, Map.of("providers", List.of(provider))));

        var capacity = ok(send("GET", "/api/v1/ai/services/lokal/capacity", anna, null));
        assertThat(capacity).containsEntry("exhausted", true).containsEntry("stale", false);
        assertThat(java.time.Instant.parse((String) capacity.get("availableAgainAt"))).isEqualTo(java.time.Instant.parse(back));
        assertThat(capacity.get("providers").toString()).contains("remaining=0").doesNotContain("key=");
        assertThat(send("GET", "/api/v1/ai/services/gibtsnicht/capacity", anna, null).statusCode()).isEqualTo(422);
    }

    // ADR 0012: Einstellungen, "Jetzt verlinken", der Worker setzt Links, die Person nimmt sie zurueck.
    @Test
    void should_linkAVault_andUndoOnlyTheLinks() throws Exception {
        var anna = user("ai-dora");
        var vault = ok(send("POST", "/api/v1/vaults", anna, Map.of("name", "Verlinkung")));
        var base = "/api/v1/vaults/" + vault.get("id");
        assertThat(ok(send("GET", base + "/linking", anna, null))).containsEntry("enabled", false).containsEntry("linkHumanNotes", true);
        assertThat(ok(send("PUT", base + "/linking", anna, Map.of("enabled", true, "linkHumanNotes", true, "service", "lokal"))))
            .containsEntry("enabled", true).containsEntry("requestedBy", "ai-dora");
        assertThat(send("PUT", base + "/linking", user("zaungast"), Map.of("enabled", false, "linkHumanNotes", true)).statusCode())
            .isEqualTo(403);

        // Erster Lauf: die KI legt zwei Notizen an (stellvertretend fuer vorhandenes Wissen).
        var first = ok(send("POST", base + "/linking/run", anna, null));
        assertThat(first).containsEntry("kind", "LINKING").containsEntry("fileName", "Verlinkung");
        var claimed = ok(send("POST", "/internal/ai/jobs/claim", WORKER, null));
        assertThat(claimed).containsEntry("kind", "LINKING");
        var internal = "/internal/ai/vaults/" + vault.get("id") + "/change-sets/" + claimed.get("changeSetId");
        var licht = ok(send("POST", internal + "/notes", WORKER, Map.of("path", "Physik/Licht.md", "text", "# Licht\n", "level", 1)));
        var pflanzen = ok(send("POST", internal + "/notes", WORKER,
            Map.of("path", "Pflanzen.md", "text", "Pflanzen brauchen Licht.\n", "level", 1)));
        ok(send("POST", "/internal/ai/jobs/" + claimed.get("jobId") + "/complete", WORKER, null));
        assertThat(ok(send("GET", base + "/linking", anna, null)).get("lastRunAt")).isNotNull();

        // Zweiter Lauf: nur verlinken.
        ok(send("POST", base + "/linking/run", anna, null));
        var run = ok(send("POST", "/internal/ai/jobs/claim", WORKER, null));
        var linking = "/internal/ai/vaults/" + vault.get("id") + "/change-sets/" + run.get("changeSetId");
        var applied = ok(send("POST", linking + "/notes/" + pflanzen.get("noteId") + "/links", WORKER,
            Map.of("links", List.of(Map.of("target", licht.get("noteId"), "anchor", "Licht", "allowRelated", false)))));
        assertThat(applied.get("applied").toString()).contains("INLINE").contains("[[Licht]]");
        assertThat(ok(send("GET", linking + "/notes/" + pflanzen.get("noteId"), WORKER, null)))
            .containsEntry("text", "Pflanzen brauchen [[Licht]].\n");
        ok(send("POST", "/internal/ai/jobs/" + run.get("jobId") + "/complete", WORKER, null));

        var report = ok(send("POST", base + "/ai/change-sets/" + run.get("changeSetId") + "/revert", anna, null));
        assertThat(report).containsEntry("reverted", 1);
        assertThat(ok(send("GET", internal + "/notes/" + pflanzen.get("noteId"), WORKER, null)))
            .containsEntry("text", "Pflanzen brauchen Licht.\n");
    }

    // ADR 0012, Stufen 2 und 3 ueber echtes HTTP und Postgres mit pgvector.
    @Test
    void should_indexSimilarNotes_judgeThem_andAskForConsent() throws Exception {
        var anna = user("ai-erna");
        var vault = ok(send("POST", "/api/v1/vaults", anna, Map.of("name", "Stufe 3")));
        var base = "/api/v1/vaults/" + vault.get("id");
        assertThat(ok(send("PUT", base + "/linking", anna, Map.of("enabled", true, "linkHumanNotes", true, "service", "lokal", "mode", "AI"))))
            .containsEntry("mode", "AI").containsEntry("aiConsent", false).containsEntry("aiConsentCount", 0);
        assertThat(ok(send("PUT", base + "/linking/consent", anna, Map.of("consent", true))))
            .containsEntry("aiConsent", true).containsEntry("aiConsentCount", 1);

        ok(send("POST", base + "/linking/run", anna, null));
        var run = ok(send("POST", "/internal/ai/jobs/claim", WORKER, null));
        var internal = "/internal/ai/vaults/" + vault.get("id") + "/change-sets/" + run.get("changeSetId");
        assertThat(ok(send("GET", "/internal/ai/vaults/" + vault.get("id") + "/linking", WORKER, null)).toString())
            .contains("mode=AI").contains("ai-erna");
        var a = ok(send("POST", internal + "/notes", WORKER, Map.of("path", "A.md", "text", "# A\n", "level", 1)));
        var b = ok(send("POST", internal + "/notes", WORKER, Map.of("path", "B.md", "text", "# B\n", "level", 1)));
        var vector = new float[384];
        vector[3] = 1;
        for (var note : List.of(a, b)) {
            assertThat(send("PUT", internal + "/notes/" + note.get("noteId") + "/embeddings", WORKER,
                Map.of("model", "m", "contentHash", "h", "chunks", List.of(Map.of("index", 0, "heading", "A", "vector", vector)))).statusCode())
                .isEqualTo(200);
        }
        assertThat(okList(send("GET", internal + "/embeddings", WORKER, null))).hasSize(2);
        assertThat(okList(send("GET", internal + "/notes/" + a.get("noteId") + "/similar", WORKER, null)))
            .singleElement().satisfies(similar -> assertThat(similar).containsEntry("noteId", b.get("noteId")));
        assertThat(okList(send("GET", base + "/notes/" + a.get("noteId") + "/similar", anna, null))).hasSize(1);

        ok(send("POST", internal + "/notes/" + a.get("noteId") + "/rejections", WORKER,
            Map.of("target", b.get("noteId"), "sourceHash", "s1", "targetHash", "t1")));
        assertThat(okList(send("GET", internal + "/notes/" + a.get("noteId") + "/rejections", WORKER, null)))
            .singleElement().satisfies(rejection -> assertThat(rejection).containsEntry("sourceHash", "s1"));
        ok(send("POST", "/internal/ai/jobs/" + run.get("jobId") + "/complete", WORKER, null));
    }
}
