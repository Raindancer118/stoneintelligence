package de.raindancer118.stoneintelligence.platform.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.raindancer118.stoneintelligence.platform.security.TestJwtSupport;
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

    private static final String WORKER_TOKEN = "e2e-worker-token-0123456789abcdef-0123456789";
    private static final String WORKER_HEADER = "X-StoneIntelligence-Worker-Token";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("STONEINTELLIGENCE_AI_SERVICES", () -> "gemini|Gemini|1; lokal|Ollama lokal|1,2");
        registry.add("STONEINTELLIGENCE_AI_WORKER_TOKEN", () -> WORKER_TOKEN);
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
        var folders = send("GET", base + "/folders", tom, null);
        assertThat(json.readValue(folders.body(), List.class)).containsExactly("Wissen");

        // Die Person sieht, was die KI getan hat, und macht es rueckgaengig.
        var sets = okList(send("GET", base + "/ai/change-sets", tom, null));
        assertThat(sets).singleElement().satisfies(set -> {
            assertThat(set).containsEntry("label", "Vorlesung.pdf").containsEntry("agent", "ki:Gemini").containsEntry("requestedBy", "ai-tom");
            assertThat(set.get("revertedAt")).isNull();
        });
        assertThat(send("GET", base + "/ai/change-sets", user("zaungast"), null).statusCode()).isEqualTo(403);
        var detail = ok(send("GET", base + "/ai/change-sets/" + changeSet.get("id"), tom, null));
        assertThat((List<?>) detail.get("changes")).hasSize(2);

        var report = ok(send("POST", base + "/ai/change-sets/" + changeSet.get("id") + "/revert", tom, null));
        assertThat(report).containsEntry("reverted", 2).containsEntry("conflicts", List.of());
        assertThat(send("GET", base + "/notes/" + written.get("noteId"), tom, null).statusCode()).isEqualTo(404);
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
        assertThat(send("GET", "/internal/ai/jobs/" + job.get("jobId") + "/document", WORKER, null).statusCode()).isEqualTo(422);
    }
}
