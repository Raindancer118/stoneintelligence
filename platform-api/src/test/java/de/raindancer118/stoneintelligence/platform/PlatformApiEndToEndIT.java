package de.raindancer118.stoneintelligence.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.raindancer118.stoneintelligence.platform.security.TestJwtSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ein einziger End-to-End-Durchlauf gegen die ECHTE, hochgefahrene Applikation (echtes HTTP via
 * {@link HttpClient}, echtes WebSocket, echtes Postgres via Testcontainers) - keine Fakes, keine
 * Mocks. Deckt genau den vertikalen Sync-Slice aus Plan.md Abschnitt 6 (Phase 2) ab: Note
 * anlegen, Ticket ausstellen, Yjs-Dokument-Updates zwischen zwei Clients live synchronisieren,
 * Late-Joiner-Catchup, Awareness NICHT persistieren, Umbenennen, Loeschen inkl. WS-Close-Signal,
 * Audit-Trail.
 *
 * <p>Bewusst ein rohes JDK-{@link HttpClient} statt eines Spring-Test-REST-Clients: Spring Boot 4
 * hat {@code TestRestTemplate} entfernt (Ersatz {@code RestTestClient} ist in Spring Framework 7
 * noch im Umbruch) - der JDK-Client ist stabil und macht diesen Test unabhaengig von der
 * jeweiligen Spring-Test-Client-API-Generation.
 *
 * <p>Braucht einen laufenden Docker-Daemon (Testcontainers) - siehe Project.md fuer den Status
 * in dieser Entwicklungsumgebung. Seit Phase 3 (OIDC-Durchsetzung) authentifiziert sich dieser
 * Test mit echten, von {@link TestJwtSupport} signierten Bearer-Tokens statt eines
 * X-Actor-Headers - der Vault selbst wird ueber die echte {@code POST /api/v1/vaults}-API
 * angelegt (bootstrapt den anlegenden Actor automatisch mit vollen Rechten), nicht mehr per
 * direktem SQL-Insert.
 */
@Testcontainers
@Import(TestJwtSupport.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformApiEndToEndIT {

    private static final byte MESSAGE_TYPE_DOC_UPDATE = 0;
    private static final byte MESSAGE_TYPE_AWARENESS = 1;
    private static final byte MESSAGE_TYPE_JOIN = 2;
    private static final byte MESSAGE_TYPE_NOTE_DELETED = 4;
    private static final int NOTE_ID_LENGTH = 36;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /** {@code Authorization: Bearer <jwt>}-Header fuer ein echtes, signiertes Test-Token. */
    private static Map<String, String> bearerAuth(String actor) {
        return Map.of("Authorization", "Bearer " + TestJwtSupport.signedJwtFor(actor));
    }

    private HttpResponse<String> postRaw(String path, Map<String, String> headers, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + path));
        headers.forEach(builder::header);
        if (body != null) {
            builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private <T> T post(String path, Map<String, String> headers, Object body, Class<T> responseType) throws Exception {
        var response = postRaw(path, headers, body);
        assertThat(response.statusCode()).as("POST %s -> %s", path, response.body()).isEqualTo(200);
        return json.readValue(response.body(), responseType);
    }

    private HttpResponse<String> patch(String path, Map<String, String> headers, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).DELETE();
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET();
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Ein Frame roher Bytes empfangen (Typ-Byte + Payload getrennt), fuer Testassertions. */
    private record ReceivedFrame(byte type, byte[] payload) {
    }

    private static final class CapturingHandler extends BinaryWebSocketHandler {
        final BlockingQueue<ReceivedFrame> received = new LinkedBlockingQueue<>();
        final CountDownLatch closedLatch = new CountDownLatch(1);
        volatile CloseStatus closeStatus;

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            var raw = new byte[message.getPayloadLength()];
            message.getPayload().get(raw);
            // NoteId-Praefix (36 Byte) wird fuer die Testassertions ignoriert - dieser Test
            // joint pro Client nur EINEN Raum, die NoteId ist also immer dieselbe.
            received.add(new ReceivedFrame(raw[0], Arrays.copyOfRange(raw, 1 + NOTE_ID_LENGTH, raw.length)));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            this.closeStatus = status;
            closedLatch.countDown();
        }

        ReceivedFrame awaitNextFrame() throws InterruptedException {
            var frame = received.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("expected a WebSocket frame within 5s").isNotNull();
            return frame;
        }
    }

    @Test
    void should_rejectUnauthenticatedRequest_withoutBearerToken() throws Exception {
        var response = get("/api/v1/vaults/" + UUID.randomUUID() + "/notes/" + UUID.randomUUID(), Map.of());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    /**
     * Ohne CORS-Konfiguration blockt der Browser jeden Cross-Origin-fetch() des Webapp-Dashboards
     * (kb.tstieh.de -> stoneintelligence.tstieh.de) schon am Preflight, bevor Spring Security
     * ueberhaupt den Bearer-Token sieht - das JS bekommt dafuer nur ein generisches "Failed to
     * fetch" ohne HTTP-Statuscode, live auf kb.tstieh.de beobachtet.
     */
    @Test
    void should_allowCrossOriginPreflight_fromWebappOrigin() throws Exception {
        var response = http.send(
            HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/vaults"))
                .header("Origin", "https://kb.tstieh.de")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("https://kb.tstieh.de");
    }

    @Test
    void should_syncCreateEditRenameDelete_endToEnd_acrossTwoRealWebSocketClients() throws Exception {
        var actorHeader = bearerAuth("tom");

        // 0) Vault ueber die echte API anlegen - bootstrapt "tom" automatisch mit vollen
        //    Rechten darin (VaultController), ganz ohne direkten SQL-Zugriff auf die Testdaten.
        var vault = post("/api/v1/vaults", actorHeader, Map.of("name", "e2e-test-vault"), Map.class);
        var vaultId = UUID.fromString((String) vault.get("id"));

        // 0b) Ein zweiter Actor OHNE jegliche Berechtigung in diesem Vault (ADR 0006 Punkt 6:
        //     die Rollen/Gruppen/ACL-Durchsetzung ist seit Phase 3 real, nicht mehr nur Domaenen-
        //     logik) muss beim Versuch, eine Note anzulegen, mit 403 abgewiesen werden.
        var forbiddenResponse = postRaw("/api/v1/vaults/" + vaultId + "/notes", bearerAuth("mallory"),
            Map.of("path", "should-not-exist.md", "noteLevel", 1));
        assertThat(forbiddenResponse.statusCode()).isEqualTo(403);

        // 1) Note anlegen (ID-first, Fehlerklasse 5)
        var created = post("/api/v1/vaults/" + vaultId + "/notes", actorHeader,
            Map.of("path", "Meeting Notes.md", "noteLevel", 1), Map.class);
        var noteId = (String) created.get("id");
        assertThat(created.get("path")).isEqualTo("Meeting Notes.md");

        // 2) Zwei vault-skopierte Sync-Tickets ausstellen (ein Ticket pro Verbindung, single-use;
        //    seit der Multiplexing-Umstellung nicht mehr notenskopiert - JOIN passiert ueber die
        //    Verbindung selbst, s. Schritt 3).
        var ticketA = post("/api/v1/vaults/" + vaultId + "/sync-tickets", actorHeader, null, Map.class);
        var ticketB = post("/api/v1/vaults/" + vaultId + "/sync-tickets", actorHeader, null, Map.class);

        // 3) Zwei ECHTE WebSocket-Clients verbinden sich, dann joint jeder explizit denselben
        //    Notiz-Raum - eine Verbindung koennte hier genausogut mehrere Raeume gleichzeitig
        //    joinen, dieser Test braucht pro Client aber nur einen.
        var wsClient = new StandardWebSocketClient();
        var handlerA = new CapturingHandler();
        var handlerB = new CapturingHandler();
        var sessionA = wsClient.execute(handlerA, wsUrl(ticketA)).get(5, TimeUnit.SECONDS);
        var sessionB = wsClient.execute(handlerB, wsUrl(ticketB)).get(5, TimeUnit.SECONDS);
        assertThat(sessionA.isOpen()).isTrue();
        assertThat(sessionB.isOpen()).isTrue();
        joinNote(sessionA, noteId);
        joinNote(sessionB, noteId);

        // 4) Client A sendet ein Dokument-Update - Client B muss es LIVE per Broadcast bekommen,
        //    Client A selbst NICHT (kein Echo).
        sendDocUpdate(sessionA, noteId, "update-1-from-a");
        var frameAtB = handlerB.awaitNextFrame();
        assertThat(frameAtB.type()).isEqualTo(MESSAGE_TYPE_DOC_UPDATE);
        assertThat(new String(frameAtB.payload(), StandardCharsets.UTF_8)).isEqualTo("update-1-from-a");
        assertThat(handlerA.received).isEmpty();

        // 5) Client B sendet ein zweites Update - Client A muss es ebenfalls live bekommen.
        sendDocUpdate(sessionB, noteId, "update-2-from-b");
        var frameAtA = handlerA.awaitNextFrame();
        assertThat(new String(frameAtA.payload(), StandardCharsets.UTF_8)).isEqualTo("update-2-from-b");

        // 6) Awareness-Nachricht: wird weitergeleitet, aber NIE als Dokument-Update gespeichert.
        sendAwareness(sessionA, noteId, "cursor-at-42");
        var awarenessAtB = handlerB.awaitNextFrame();
        assertThat(awarenessAtB.type()).isEqualTo(MESSAGE_TYPE_AWARENESS);
        assertThat(new String(awarenessAtB.payload(), StandardCharsets.UTF_8)).isEqualTo("cursor-at-42");

        var persistedSnapshotCount = JdbcClient.create(dataSource)
            .sql("SELECT count(*) FROM platform.note_snapshots WHERE note_id = :noteId")
            .param("noteId", UUID.fromString(noteId))
            .query(Integer.class)
            .single();
        assertThat(persistedSnapshotCount)
            .as("nur die 2 Dokument-Updates duerfen persistiert sein, nicht die Awareness-Nachricht")
            .isEqualTo(2);

        // 7) Ein DRITTER, spaeter beitretender Client bekommt per Late-Joiner-Catchup BEIDE
        //    bisherigen Dokument-Updates in Reihenfolge.
        var ticketC = post("/api/v1/vaults/" + vaultId + "/sync-tickets", actorHeader, null, Map.class);
        var handlerC = new CapturingHandler();
        var sessionC = wsClient.execute(handlerC, wsUrl(ticketC)).get(5, TimeUnit.SECONDS);
        joinNote(sessionC, noteId);
        var catchup1 = handlerC.awaitNextFrame();
        var catchup2 = handlerC.awaitNextFrame();
        assertThat(new String(catchup1.payload(), StandardCharsets.UTF_8)).isEqualTo("update-1-from-a");
        assertThat(new String(catchup2.payload(), StandardCharsets.UTF_8)).isEqualTo("update-2-from-b");
        sessionC.close();

        // 8) Umbenennen ueber REST (ID-first, NoteId bleibt stabil).
        var renameResponse = patch("/api/v1/vaults/" + vaultId + "/notes/" + noteId, actorHeader,
            Map.of("path", "Q3 Meeting Notes.md"));
        assertThat(renameResponse.statusCode()).isEqualTo(200);
        var renamed = json.readValue(renameResponse.body(), Map.class);
        assertThat(renamed.get("path")).isEqualTo("Q3 Meeting Notes.md");
        assertThat(renamed.get("id")).isEqualTo(noteId);

        // 9) Loeschen ueber REST - der noch verbundene Client B muss eine NOTE_DELETED-Nachricht
        //    fuer GENAU diese Notiz bekommen (Anforderungen.md: Loeschungen live synchronisieren).
        //    Die Verbindung selbst bleibt bestehen - seit der Multiplexing-Umstellung koennte
        //    dieselbe Verbindung noch weitere, nicht geloeschte Notizen bedienen.
        var deleteHeaders = new java.util.HashMap<>(actorHeader);
        deleteHeaders.put("X-Operation-Id", "e2e-delete-op-1");
        var deleteResponse = delete("/api/v1/vaults/" + vaultId + "/notes/" + noteId, deleteHeaders);
        assertThat(deleteResponse.statusCode()).isEqualTo(200);
        var tombstone = json.readValue(deleteResponse.body(), Map.class);
        assertThat(tombstone.get("operationId")).isEqualTo("e2e-delete-op-1");

        var deletionNotice = handlerB.awaitNextFrame();
        assertThat(deletionNotice.type()).isEqualTo(MESSAGE_TYPE_NOTE_DELETED);
        assertThat(sessionB.isOpen()).as("Verbindung bleibt trotz Notiz-Loeschung offen").isTrue();

        // 10) Die Note ist wirklich weg.
        var getAfterDelete = get("/api/v1/vaults/" + vaultId + "/notes/" + noteId, actorHeader);
        assertThat(getAfterDelete.statusCode()).isEqualTo(404);

        // 11) Audit-Trail zeigt create + rename + delete, jeweils mit dem richtigen Actor.
        var auditResponse = get("/api/v1/vaults/" + vaultId + "/notes/" + noteId + "/audit", actorHeader);
        assertThat(auditResponse.statusCode()).isEqualTo(200);
        List<Map<String, Object>> auditEvents = json.readValue(auditResponse.body(), List.class);
        assertThat(auditEvents).extracting(event -> event.get("action"))
            .containsExactly("note.created", "note.renamed", "note.deleted");
        assertThat(auditEvents).allSatisfy(event -> assertThat(event.get("actor")).isEqualTo("tom"));

        sessionA.close();
    }

    private String wsUrl(Map<?, ?> issuedTicket) {
        return "ws://localhost:" + port + "/ws/sync?ticket=" + issuedTicket.get("token");
    }

    /** Ohne JOIN akzeptiert der Server weder Catchup noch Doc-Update-/Awareness-Nachrichten fuer diese NoteId. */
    private void joinNote(WebSocketSession session, String noteId) throws Exception {
        session.sendMessage(new BinaryMessage(frame(MESSAGE_TYPE_JOIN, noteId, new byte[0])));
    }

    private void sendDocUpdate(WebSocketSession session, String noteId, String payload) throws Exception {
        session.sendMessage(new BinaryMessage(frame(MESSAGE_TYPE_DOC_UPDATE, noteId, payload.getBytes(StandardCharsets.UTF_8))));
    }

    private void sendAwareness(WebSocketSession session, String noteId, String payload) throws Exception {
        session.sendMessage(new BinaryMessage(frame(MESSAGE_TYPE_AWARENESS, noteId, payload.getBytes(StandardCharsets.UTF_8))));
    }

    /** {@code [1 Byte Typ][36 Byte NoteId als ASCII-UUID][Rest: Payload]} - s. SyncFrame im Hauptcode. */
    private ByteBuffer frame(byte type, String noteId, byte[] payload) {
        var noteIdBytes = noteId.getBytes(StandardCharsets.US_ASCII);
        var buffer = ByteBuffer.allocate(1 + noteIdBytes.length + payload.length);
        buffer.put(type).put(noteIdBytes).put(payload);
        buffer.flip();
        return buffer;
    }
}
