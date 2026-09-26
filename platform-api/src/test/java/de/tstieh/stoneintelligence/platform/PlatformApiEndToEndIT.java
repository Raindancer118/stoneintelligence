package de.tstieh.stoneintelligence.platform;

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
import de.tstieh.stoneintelligence.platform.security.TestJwtSupport;
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
@Import({TestJwtSupport.class, PlatformApiEndToEndIT.RecordingMailConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformApiEndToEndIT {

    private static final byte MESSAGE_TYPE_DOC_UPDATE = 0;
    private static final byte MESSAGE_TYPE_AWARENESS = 1;
    private static final byte MESSAGE_TYPE_JOIN = 2;
    private static final byte MESSAGE_TYPE_NOTE_DELETED = 4;
    private static final byte MESSAGE_TYPE_VAULT_NOTE_DELETED = 7;
    private static final byte MESSAGE_TYPE_VAULT_NOTE_RENAMED = 8;
    /**
     * Server->Client: komplette Late-Joiner-Historie fuer diese Notiz wurde gesendet (s.
     * {@code SyncFrame.TYPE_CATCHUP_COMPLETE}). Reine Protokoll-Buchhaltung, kein fachlicher
     * Payload - wird in {@code CapturingHandler} bewusst gar nicht erst eingesammelt, sonst
     * wuerden bestehende "keine weitere Nachricht"-Assertions (z. B. kein Echo an den Sender)
     * faelschlich auf dieses Marker-Frame anschlagen.
     */
    private static final byte MESSAGE_TYPE_CATCHUP_COMPLETE = 5;
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

    /** Freigabe setzen (ADR 0011) - Ordner muss existieren; {@code permissions == null} = wie im Vault. */
    private void putFolderGrant(String vaultPath, Map<String, String> headers, String folder, String subject,
                                List<String> permissions) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("scopeType", "USER");
        body.put("subject", subject);
        body.put("permissions", permissions);
        var builder = HttpRequest.newBuilder(URI.create(baseUrl() + vaultPath + "/folders/access/grants?path="
                + java.net.URLEncoder.encode(folder, java.nio.charset.StandardCharsets.UTF_8)))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        headers.forEach(builder::header);
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("grant on '%s'", folder).isEqualTo(200);
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
        final CountDownLatch caughtUp = new CountDownLatch(1);
        volatile CloseStatus closeStatus;

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            var raw = new byte[message.getPayloadLength()];
            message.getPayload().get(raw);
            if (raw[0] == MESSAGE_TYPE_CATCHUP_COMPLETE) {
                caughtUp.countDown();
                return;
            }
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
    void should_allowOnlyOneConcurrentCompareAndAppend_acrossRepositoryInstances() throws Exception {
        var auth = bearerAuth("concurrent-editor");
        var vault = post("/api/v1/vaults", auth, Map.of("name", "Concurrent"), Map.class);
        var note = post("/api/v1/vaults/" + vault.get("id") + "/notes", auth,
            Map.of("path", "concurrent.md", "noteLevel", 1), Map.class);
        var noteId = de.tstieh.stoneintelligence.domain.id.NoteId.of(note.get("id").toString());
        var one = new de.tstieh.stoneintelligence.platform.sync.relay.JdbcSnapshotStore(JdbcClient.create(dataSource));
        var two = new de.tstieh.stoneintelligence.platform.sync.relay.JdbcSnapshotStore(JdbcClient.create(dataSource));
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return one.appendIfCurrent(noteId, 0, new byte[] {1, 2}); });
            var b = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return two.appendIfCurrent(noteId, 0, new byte[] {3, 4}); });
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                .filteredOn(java.util.Optional::isPresent).hasSize(1);
        }
        assertThat(one.listSince(noteId, 0)).hasSize(1);
    }

    /**
     * Das Plugin gleicht geschlossene Notizen periodisch ab. Ohne Revisionsnummer in der Liste
     * muesste es dafuer JEDE Notiz joinen und ihre komplette Historie laden, nur um festzustellen,
     * dass sich nichts geaendert hat.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class RecordingMailConfig {
        static final java.util.List<de.tstieh.stoneintelligence.platform.invitation.OutgoingMail> SENT =
            new java.util.concurrent.CopyOnWriteArrayList<>();

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        de.tstieh.stoneintelligence.platform.invitation.Mailer recordingMailer() {
            return SENT::add;
        }
    }

    private String lastInviteToken(String email) {
        var mail = RecordingMailConfig.SENT.stream().filter(sent -> sent.to().equals(email)).reduce((a, b) -> b).orElseThrow();
        var matcher = java.util.regex.Pattern.compile("/invite/([A-Za-z0-9_-]+)").matcher(mail.text());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    /**
     * Einladen per E-Mail bis zur Mitarbeit: Einladung ansehen ohne Login, annehmen mit Login,
     * danach Zugriff. Zweitverwendung und Einladen ohne Verwaltungsrecht werden abgewiesen.
     */
    @Test
    void should_inviteByEmail_showTheInvitationPublicly_andGrantAccessOnAccept() throws Exception {
        var owner = bearerAuth("inviting-owner");
        var vault = post("/api/v1/vaults", owner, Map.of("name", "Einladungs-Vault"), Map.class);
        var base = "/api/v1/vaults/" + vault.get("id");

        var invited = post(base + "/invitations", owner, Map.of("email", "Neu.Person@example.org", "access", "EDIT"), Map.class);
        assertThat(invited.get("status")).isEqualTo("INVITED");
        var token = lastInviteToken("neu.person@example.org");

        var publicView = get("/api/v1/invitations/" + token, Map.of());
        assertThat(publicView.statusCode()).as(publicView.body()).isEqualTo(200);
        var info = json.readTree(publicView.body());
        assertThat(info.get("state").asText()).isEqualTo("PENDING");
        assertThat(info.get("vaultName").asText()).isEqualTo("Einladungs-Vault");
        assertThat(info.get("maskedEmail").asText()).isEqualTo("n***@example.org");
        assertThat(json.readTree(get(base + "/invitations", owner).body()).size()).isEqualTo(1);

        var newcomer = bearerAuth("newcomer");
        assertThat(get(base + "/notes", newcomer).statusCode()).isEqualTo(403);
        var accepted = postRaw("/api/v1/invitations/" + token + "/accept", newcomer, null);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(get(base + "/notes", newcomer).statusCode()).isEqualTo(200);
        assertThat(postRaw(base + "/invitations", newcomer, Map.of("email", "x@example.org")).statusCode())
            .as("Mitbearbeiter duerfen nicht selbst einladen").isEqualTo(403);
        var reused = postRaw("/api/v1/invitations/" + token + "/accept", bearerAuth("mallory"), null);
        assertThat(reused.statusCode()).isEqualTo(422);
        assertThat(json.readTree(reused.body()).get("detail").asText()).isEqualTo("Diese Einladung wurde bereits angenommen.");
        assertThat(postRaw("/api/v1/invitations/" + token + "/accept", Map.of(), null).statusCode()).isEqualTo(401);
    }

    @Test
    void should_joinAutomatically_whenTheInvitedEmailSignsIn() throws Exception {
        var owner = bearerAuth("auto-owner");
        var vault = post("/api/v1/vaults", owner, Map.of("name", "Auto-Vault"), Map.class);
        post("/api/v1/vaults/" + vault.get("id") + "/invitations", owner, Map.of("email", "auto@example.org"), Map.class);

        var signedIn = Map.of("Authorization", "Bearer " + TestJwtSupport.signedJwtFor("auto-user", "auto@example.org"));
        var vaults = json.readTree(get("/api/v1/vaults", signedIn).body());

        assertThat(vaults).extracting(node -> node.get("name").asText()).contains("Auto-Vault");
    }

    @Test
    void should_reportContentRevision_perNote_inReconciliationList() throws Exception {
        var auth = bearerAuth("revision-reader");
        var vault = post("/api/v1/vaults", auth, Map.of("name", "Revisions"), Map.class);
        var base = "/api/v1/vaults/" + vault.get("id") + "/notes";
        var untouched = post(base, auth, Map.of("path", "untouched.md", "noteLevel", 1), Map.class);
        var edited = post(base, auth, Map.of("path", "edited.md", "noteLevel", 1), Map.class);
        var contentPath = base + "/" + edited.get("id") + "/content";
        assertThat(postRaw(contentPath, auth, Map.of("expectedRevision", 0, "update", "AQID")).statusCode()).isEqualTo(200);
        assertThat(postRaw(contentPath, auth, Map.of("expectedRevision", 1, "update", "BAUG")).statusCode()).isEqualTo(200);

        var revisions = new java.util.HashMap<String, Long>();
        json.readTree(get(base, auth).body()).get("notes")
            .forEach(note -> revisions.put(note.get("id").asText(), note.get("revision").asLong()));

        assertThat(revisions).containsEntry(untouched.get("id").toString(), 0L)
            .containsEntry(edited.get("id").toString(), 2L);
    }

    @Test
    void should_rejectUnsafePathsDuplicateNames_andMovesIntoDeniedFolders() throws Exception {
        var auth = bearerAuth("path-editor");
        var vault = post("/api/v1/vaults", auth, Map.of("name", "Safe paths"), Map.class);
        var base = "/api/v1/vaults/" + vault.get("id");
        for (var path : List.of("../outside.md", "/absolute.md", ".obsidian/settings.md", "a//b.md", "not-markdown.txt")) {
            assertThat(postRaw(base + "/notes", auth, Map.of("path", path, "noteLevel", 1)).statusCode())
                .as(path).isEqualTo(400);
        }
        var note = post(base + "/notes", auth, Map.of("path", "visible.md", "noteLevel", 1), Map.class);
        assertThat(postRaw(base + "/notes", auth, Map.of("path", "visible.md", "noteLevel", 1)).statusCode()).isEqualTo(409);
        assertThat(postRaw(base + "/folders", auth, Map.of("path", "private")).statusCode()).isEqualTo(200);
        putFolderGrant(base, auth, "private", "path-editor", List.of());
        assertThat(patch(base + "/notes/" + note.get("id"), auth, Map.of("path", "private/hidden.md")).statusCode()).isEqualTo(403);
    }

    @Test
    void should_readAndConditionallySaveBrowserContent_withoutLosingConcurrentEdits() throws Exception {
        var auth = bearerAuth("browser-editor");
        var vault = post("/api/v1/vaults", auth, Map.of("name", "Browser notes"), Map.class);
        var path = "/api/v1/vaults/" + vault.get("id") + "/notes";
        var note = post(path, auth, Map.of("path", "welcome.md", "noteLevel", 1), Map.class);
        var contentPath = path + "/" + note.get("id") + "/content";

        var empty = get(contentPath, auth);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(json.readTree(empty.body()).get("revision").asLong()).isZero();
        var ticket = post("/api/v1/vaults/" + vault.get("id") + "/sync-tickets", auth, null, Map.class);
        var handler = new CapturingHandler();
        var session = new StandardWebSocketClient().execute(handler, wsUrl(ticket)).get(5, TimeUnit.SECONDS);
        try {
        joinNote(session, note.get("id").toString());
        assertThat(handler.caughtUp.await(5, TimeUnit.SECONDS)).isTrue();
        var payload = Map.of("expectedRevision", 0, "update", "AQID");
        var saved = postRaw(contentPath, auth, payload);
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(json.readTree(saved.body()).get("revision").asLong()).isEqualTo(1);
        assertThat(handler.awaitNextFrame().payload()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(postRaw(contentPath, auth, payload).statusCode()).isEqualTo(409);
        var loaded = json.readTree(get(contentPath, auth).body());
        assertThat(loaded.get("updates").get(0).asText()).isEqualTo("AQID");
        assertThat(loaded.get("updates").size()).isEqualTo(1);
        assertThat(postRaw(contentPath, auth, Map.of("expectedRevision", 1, "update", "")).statusCode())
            .isEqualTo(400);
        assertThat(get(contentPath, bearerAuth("stranger")).statusCode()).isEqualTo(403);
        } finally { session.close(); }

        var base = "/api/v1/vaults/" + vault.get("id");
        var role = post(base + "/roles", auth, Map.of("name", "reader", "permissions", List.of("READ")), Map.class);
        var group = post(base + "/groups", auth, Map.of("name", "readers"), Map.class);
        assertThat(postRaw(base + "/groups/" + group.get("id") + "/members", auth, Map.of("subject", "reader")).statusCode()).isEqualTo(200);
        assertThat(postRaw(base + "/groups/" + group.get("id") + "/roles/" + role.get("id"), auth, null).statusCode()).isEqualTo(200);
        assertThat(get(contentPath, bearerAuth("reader")).statusCode()).isEqualTo(200);
        assertThat(postRaw(contentPath, bearerAuth("reader"), Map.of("expectedRevision", 1, "update", "AQID")).statusCode()).isEqualTo(403);
        var encrypted = post(base + "/notes", auth, Map.of("path", "encrypted.md", "noteLevel", 101), Map.class);
        assertThat(get(base + "/notes/" + encrypted.get("id") + "/content", auth).statusCode()).isEqualTo(409);
    }

    @Test
    void should_hideDeniedPaths_fromBrowserListsContentAndAudit() throws Exception {
        var auth = bearerAuth("path-reader");
        var vault = post("/api/v1/vaults", auth, Map.of("name", "Path rules"), Map.class);
        var base = "/api/v1/vaults/" + vault.get("id");
        var note = post(base + "/notes", auth, Map.of("path", "private/secret.md", "noteLevel", 1), Map.class);
        putFolderGrant(base, auth, "private", "path-reader", List.of());

        var list = json.readTree(get(base + "/notes", auth).body());
        assertThat(list.get("notes").size()).isZero();
        assertThat(list.get("complete").asBoolean()).isTrue();
        assertThat(get(base + "/notes/" + note.get("id"), auth).statusCode()).isEqualTo(403);
        assertThat(get(base + "/notes/" + note.get("id") + "/history", auth).statusCode()).isEqualTo(403);
        assertThat(get(base + "/notes/" + note.get("id") + "/content", auth).statusCode()).isEqualTo(403);
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

    private List<Object> folders(String base, Map<String, String> auth) throws Exception {
        var response = get(base + "/folders", auth);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readValue(response.body(), new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() { });
    }

    private static String encoded(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // Ordner sind eigene Objekte: leer angelegte Ordner kommen bei allen an, ein geloeschter
    // verschwindet ueberall, statt auf den anderen Geraeten leer liegen zu bleiben.
    @Test
    void should_syncFolders_includingEmptyOnes_andAnnounceChangesToSubscribedDevices() throws Exception {
        var auth = bearerAuth("folder-editor");
        var vault = post("/api/v1/vaults", auth, Map.of("name", "Ordner"), Map.class);
        var base = "/api/v1/vaults/" + vault.get("id");
        var modern = new CapturingHandler();
        var legacy = new CapturingHandler();
        var wsClient = new StandardWebSocketClient();
        var modernSession = wsClient.execute(modern, wsUrl(post(base + "/sync-tickets", auth, null, Map.class))).get(5, TimeUnit.SECONDS);
        var legacySession = wsClient.execute(legacy, wsUrl(post(base + "/sync-tickets", auth, null, Map.class))).get(5, TimeUnit.SECONDS);
        modernSession.sendMessage(new BinaryMessage(frame((byte) 11, new UUID(0, 0).toString(), new byte[0])));
        Thread.sleep(200);

        assertThat(post(base + "/folders", auth, Map.of("path", "Leer/Unter"), Map.class)).containsEntry("path", "Leer/Unter");
        post(base + "/notes", auth, Map.of("path", "Projekt/A.md", "noteLevel", 1), Map.class);
        assertThat(folders(base, auth)).containsExactly("Leer", "Leer/Unter", "Projekt");
        var announced = modern.awaitNextFrame();
        assertThat(announced.type()).isEqualTo((byte) 12);
        assertThat(new String(announced.payload(), StandardCharsets.UTF_8)).isEqualTo("Leer/Unter");

        assertThat(postRaw(base + "/folders/rename", auth, Map.of("from", "Leer", "to", "Archiv/Leer")).statusCode()).isEqualTo(200);
        assertThat(folders(base, auth)).containsExactly("Archiv", "Archiv/Leer", "Archiv/Leer/Unter", "Projekt");
        assertThat(delete(base + "/folders?path=" + encoded("Archiv"), auth).statusCode()).isEqualTo(200);
        assertThat(folders(base, auth)).containsExactly("Projekt");

        for (var path : List.of("../raus", ".obsidian", "a//b", "")) {
            assertThat(postRaw(base + "/folders", auth, Map.of("path", path)).statusCode()).as(path).isEqualTo(400);
        }
        assertThat(postRaw(base + "/folders/rename", auth, Map.of("from", "Projekt", "to", "Projekt/In")).statusCode()).isEqualTo(400);
        assertThat(postRaw(base + "/folders", bearerAuth("mallory"), Map.of("path", "X")).statusCode()).isEqualTo(403);
        assertThat(postRaw(base + "/folders", auth, Map.of("path", "Geheim")).statusCode()).isEqualTo(200);
        putFolderGrant(base, auth, "Geheim", "folder-editor", List.of());
        assertThat(postRaw(base + "/folders", auth, Map.of("path", "Geheim/X")).statusCode()).isEqualTo(403);

        // Aeltere Plugins behandeln unbekannte Nachrichtentypen als Yjs-Update - nie zustellen.
        assertThat(legacy.received.stream().map(ReceivedFrame::type)).doesNotContain((byte) 12);
        modernSession.close();
        legacySession.close();
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

        //    Die Umbenennung geht zusaetzlich als vault-weite Bestandsankuendigung an JEDE
        //    Verbindung des Vaults - damit erfahren auch Geraete davon, die die Notiz gar nicht
        //    gejoint haben (s. VaultAnnouncementService). Client B ist hier zwar gejoint, bekommt
        //    sie aber ueber denselben Weg.
        var renameNotice = handlerB.awaitNextFrame();
        assertThat(renameNotice.type()).isEqualTo(MESSAGE_TYPE_VAULT_NOTE_RENAMED);
        assertThat(new String(renameNotice.payload(), StandardCharsets.UTF_8)).isEqualTo("Q3 Meeting Notes.md");

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

        //    Zwei Nachrichten mit unterschiedlichem Zweck: NOTE_DELETED beendet den Notiz-Raum
        //    dieser gejointen Verbindung, VAULT_NOTE_DELETED ist die vault-weite Bestandsmeldung,
        //    die auch nicht gejointe Geraete erreicht. Die Reihenfolge zwischen beiden ist nicht
        //    festgelegt, deshalb wird auf das Paar geprueft, nicht auf eine feste Abfolge.
        var deletionNotices = List.of(handlerB.awaitNextFrame(), handlerB.awaitNextFrame());
        assertThat(deletionNotices).extracting(ReceivedFrame::type)
            .containsExactlyInAnyOrder(MESSAGE_TYPE_NOTE_DELETED, MESSAGE_TYPE_VAULT_NOTE_DELETED);
        assertThat(sessionB.isOpen()).as("Verbindung bleibt trotz Notiz-Loeschung offen").isTrue();

        // 10) Die Note ist wirklich weg.
        var getAfterDelete = get("/api/v1/vaults/" + vaultId + "/notes/" + noteId, actorHeader);
        assertThat(getAfterDelete.statusCode()).isEqualTo(404);

        // 11) Audit-Trail zeigt create + rename + delete, jeweils mit dem richtigen Actor.
        var auditResponse = get("/api/v1/vaults/" + vaultId + "/notes/" + noteId + "/history", actorHeader);
        assertThat(auditResponse.statusCode()).isEqualTo(200);
        List<Map<String, Object>> auditEvents = (List<Map<String, Object>>) json.readValue(auditResponse.body(), Map.class).get("events");
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
