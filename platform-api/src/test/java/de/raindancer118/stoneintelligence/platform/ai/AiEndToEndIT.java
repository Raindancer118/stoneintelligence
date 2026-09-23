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
}
