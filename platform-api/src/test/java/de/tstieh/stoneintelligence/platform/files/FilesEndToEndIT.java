package de.tstieh.stoneintelligence.platform.files;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tstieh.stoneintelligence.platform.security.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Dateien synchronisieren (ADR 0009) gegen die echte Anwendung: Postgres, Dateispeicher, HTTP, WebSocket. */
@Testcontainers
@Import(TestJwtSupport.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FilesEndToEndIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
        .withDatabaseName("stoneintelligence").withUsername("stoneintelligence").withPassword("test");

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("STONEINTELLIGENCE_FILE_STORAGE_DIR", () -> storage.toString());
        registry.add("STONEINTELLIGENCE_FILE_MAX_MB", () -> "1");
        registry.add("STONEINTELLIGENCE_FILE_QUOTA_MB", () -> "3");
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private static Map<String, String> user(String name) {
        return Map.of("Authorization", "Bearer " + TestJwtSupport.signedJwtFor(name));
    }

    private HttpRequest.Builder request(String path, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        headers.forEach(builder::header);
        return builder;
    }

    private HttpResponse<String> sendJson(String method, String path, Map<String, String> headers, Object body) throws Exception {
        var builder = request(path, headers);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String path, Map<String, String> headers, String ifMatch, byte[] content) throws Exception {
        var builder = request(path, headers).header("Content-Type", "application/pdf").PUT(HttpRequest.BodyPublishers.ofByteArray(content));
        if (ifMatch != null) {
            builder.header("If-Match", ifMatch);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readValue(response.body(), new TypeReference<>() { });
    }

    private static final class Capturing extends BinaryWebSocketHandler {
        final BlockingQueue<Byte> types = new LinkedBlockingQueue<>();

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            types.add(message.getPayload().get(0));
        }
    }

    private static ByteBuffer frame(byte type) {
        var id = new java.util.UUID(0, 0).toString().getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(1 + id.length).put(type).put(id).flip();
    }

    @Test
    void should_syncAFile_withRevisions_conflicts_limits_andSafeDownloads() throws Exception {
        var tom = user("file-tom");
        var vault = ok(sendJson("POST", "/api/v1/vaults", tom, Map.of("name", "Dateien")));
        var base = "/api/v1/vaults/" + vault.get("id");

        var ws = new StandardWebSocketClient();
        var aware = new Capturing();
        var older = new Capturing();
        var awareSession = ws.execute(aware, "ws://localhost:" + port + "/ws/sync?ticket="
            + ok(sendJson("POST", base + "/sync-tickets", tom, null)).get("token")).get(5, TimeUnit.SECONDS);
        var olderSession = ws.execute(older, "ws://localhost:" + port + "/ws/sync?ticket="
            + ok(sendJson("POST", base + "/sync-tickets", tom, null)).get("token")).get(5, TimeUnit.SECONDS);
        awareSession.sendMessage(new BinaryMessage(frame((byte) 13)));
        Thread.sleep(200);

        // Anlegen: Pfadregeln wie bei Notizen, aber nie Markdown.
        var file = ok(sendJson("POST", base + "/files", tom, Map.of("path", "Anhänge/Skript.pdf")));
        assertThat(file).containsEntry("kind", "FILE").containsEntry("path", "Anhänge/Skript.pdf");
        var content = base + "/files/" + file.get("id") + "/content";
        assertThat(sendJson("POST", base + "/files", tom, Map.of("path", "Notiz.md")).statusCode()).isEqualTo(400);
        assertThat(sendJson("POST", base + "/files", user("mallory"), Map.of("path", "x.pdf")).statusCode()).isEqualTo(403);

        // Hochladen nur mit der Fassung, auf der die Aenderung beruht.
        var pdf = "%PDF-1.7 Skript".getBytes(StandardCharsets.US_ASCII);
        assertThat(upload(content, tom, null, pdf).statusCode()).isEqualTo(428);
        var first = ok(upload(content, tom, "\"0\"", pdf));
        assertThat(first).containsEntry("revision", 1).containsEntry("size", pdf.length);
        var conflict = upload(content, tom, "\"0\"", "%PDF anders".getBytes(StandardCharsets.US_ASCII));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.headers().firstValue("X-Current-Revision")).contains("1");

        // Ausliefern: immer als Anhang, nie ausfuehrbar im Kontext der API.
        var download = http.send(request(content, tom).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(pdf);
        assertThat(download.headers().firstValue("ETag")).contains("\"1\"");
        assertThat(download.headers().firstValue("X-Content-SHA256")).contains((String) first.get("sha256"));
        assertThat(download.headers().firstValue("Content-Disposition")).get().asString().startsWith("attachment").contains("Skript.pdf");
        assertThat(download.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(download.headers().firstValue("Content-Security-Policy")).get().asString().contains("sandbox");

        // Aeltere Clients sehen Dateien weder in der Liste noch in Ankuendigungen.
        var notesOnly = json.readTree(sendJson("GET", base + "/notes", tom, null).body()).path("notes");
        assertThat(notesOnly).isEmpty();
        var withFiles = json.readTree(sendJson("GET", base + "/notes?kinds=note,file", tom, null).body()).path("notes");
        assertThat(withFiles).hasSize(1);
        assertThat(withFiles.get(0).path("kind").asText()).isEqualTo("FILE");
        assertThat(withFiles.get(0).path("revision").asLong()).isEqualTo(1);
        assertThat(withFiles.get(0).path("sha256").asText()).isEqualTo(first.get("sha256"));
        assertThat(aware.types.poll(5, TimeUnit.SECONDS)).isEqualTo((byte) 6);
        assertThat(aware.types.poll(5, TimeUnit.SECONDS)).isEqualTo((byte) 9);
        assertThat(older.types.poll(500, TimeUnit.MILLISECONDS)).as("Datei-Ereignis an aelteres Plugin").isNull();

        // Umbenennen und Loeschen laufen ueber dieselben Wege wie bei Notizen.
        assertThat(sendJson("PATCH", base + "/notes/" + file.get("id"), tom, Map.of("path", "Archiv/Skript.pdf")).statusCode()).isEqualTo(200);
        assertThat(sendJson("PATCH", base + "/notes/" + file.get("id"), tom, Map.of("path", "Archiv/Skript.md")).statusCode()).isEqualTo(400);
        assertThat(sendJson("GET", base + "/notes/" + file.get("id") + "/content", tom, null).statusCode()).isEqualTo(409);

        // Grenzen: je Datei 1 MB, je Vault 3 MB - das Plugin fragt sie vorab ab, statt 200 MB umsonst zu senden.
        assertThat(ok(sendJson("GET", "/api/v1/files/limits", tom, null)))
            .containsEntry("maxFileBytes", 1024 * 1024).containsEntry("vaultQuotaBytes", 3 * 1024 * 1024);
        var big = ok(sendJson("POST", base + "/files", tom, Map.of("path", "gross.bin")));
        assertThat(upload(base + "/files/" + big.get("id") + "/content", tom, "\"0\"", new byte[1024 * 1024 + 1]).statusCode()).isEqualTo(413);
        // Drei Teile knapp unter 1 MB passen noch in 3 MB, der vierte nicht mehr.
        for (var i = 0; i < 4; i++) {
            var part = ok(sendJson("POST", base + "/files", tom, Map.of("path", "teil" + i + ".bin")));
            var response = upload(base + "/files/" + part.get("id") + "/content", tom, "\"0\"", new byte[1_040_000 + i]);
            assertThat(response.statusCode()).as("Teil " + i).isEqualTo(i < 3 ? 200 : 507);
        }

        var deleted = request(base + "/notes/" + file.get("id"), tom).header("X-Operation-Id", "del-1").DELETE().build();
        assertThat(http.send(deleted, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(http.send(request(content, tom).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        awareSession.close();
        olderSession.close();
    }
}
