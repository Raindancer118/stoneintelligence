package de.raindancer118.stoneintelligence.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.raindancer118.stoneintelligence.platform.security.TestJwtSupport;
import jakarta.websocket.ContainerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
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
 * Simuliert zwei (bzw. drei) unabhaengige Endgeraete, die sich einen vollen Vault ueber denselben
 * Server teilen - echte HTTP/WebSocket-Verbindungen gegen die tatsaechlich hochgefahrene
 * Applikation, kein Mock. Deckt an, was Tom explizit angefragt hat: Uebertragung eines vollen
 * Vaults unter erschwerenden Umstaenden (mehrseitige Reconciliation, ungewoehnlich grosse
 * Notizen, abgebrochene und wiederhergestellte Verbindungen, Loeschung waehrend der
 * Uebertragung, fehlende Berechtigung, ungedrosselte Join-Bursts).
 */
@Testcontainers
@Import(TestJwtSupport.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VaultSyncSimulationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    private static final byte MESSAGE_TYPE_DOC_UPDATE = 0;
    private static final byte MESSAGE_TYPE_JOIN = 2;
    private static final byte MESSAGE_TYPE_LEAVE = 3;
    private static final byte MESSAGE_TYPE_NOTE_DELETED = 4;
    /**
     * Server->Client: komplette Late-Joiner-Historie fuer diese Notiz wurde gesendet (s.
     * {@code SyncFrame.TYPE_CATCHUP_COMPLETE}). Reine Protokoll-Buchhaltung, kein fachlicher
     * Payload - {@code awaitFrame} ueberliest sie transparent, damit bestehende Assertions auf
     * "die naechste inhaltliche Nachricht" nicht ploetzlich dieses Marker-Frame statt der
     * erwarteten Nutzdaten sehen.
     */
    private static final byte MESSAGE_TYPE_CATCHUP_COMPLETE = 5;
    private static final byte MESSAGE_TYPE_VAULT_NOTE_CREATED = 6;
    private static final byte MESSAGE_TYPE_VAULT_NOTE_DELETED = 7;
    private static final byte MESSAGE_TYPE_VAULT_NOTE_RENAMED = 8;
    private static final int NOTE_ID_LENGTH = 36;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    /**
     * Der JSR-356-Client-Container (nicht der Server-Container aus WebSocketConfig!) hat
     * denselben 8-KiB-Default fuer eingehende Nachrichten - ohne diese Anhebung wuerde JEDES
     * simulierte Geraet grosse Catchup-Frames stillschweigend nie empfangen, obwohl der Server
     * sie nachweislich (per Debug-Log verifiziert) korrekt sendet. Reiner Testclient-Aspekt -
     * echte Browser/Electron/Capacitor-WebSockets haben dieses kleine Limit nicht. Explizit an
     * genau die {@link StandardWebSocketClient}-Instanz gebunden statt auf den ContainerProvider-
     * Singleton zu vertrauen (der pro Client separat instanziiert sein kann).
     */
    private final StandardWebSocketClient wsClient = createWsClient();

    private static StandardWebSocketClient createWsClient() {
        var container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxBinaryMessageBufferSize(16 * 1024 * 1024);
        container.setDefaultMaxTextMessageBufferSize(16 * 1024 * 1024);
        return new StandardWebSocketClient(container);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

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

    /**
     * Gibt {@code subject} Lesezugriff AUSSCHLIESSLICH unterhalb von {@code pathPrefix}: eine
     * Gruppe mit reiner READ-Rolle, plus zwei Pfadregeln (alles verwehren, den erlaubten Praefix
     * wieder zulassen - der laengste Praefix gewinnt, s. {@code PathRules}).
     */
    private void grantReadOnlyOnPath(String vaultId, String subject, String pathPrefix) throws Exception {
        var owner = bearerAuth(vaultOwners.get(vaultId));
        var role = post("/api/v1/vaults/" + vaultId + "/roles", owner,
            Map.of("name", "leser-" + subject, "permissions", List.of("READ")), Map.class);
        var group = post("/api/v1/vaults/" + vaultId + "/groups", owner,
            Map.of("name", "gruppe-" + subject), Map.class);
        post("/api/v1/vaults/" + vaultId + "/groups/" + group.get("id") + "/members", owner,
            Map.of("subject", subject), Map.class);
        post("/api/v1/vaults/" + vaultId + "/groups/" + group.get("id") + "/roles/" + role.get("id"),
            owner, null, Map.class);
        post("/api/v1/vaults/" + vaultId + "/path-rules", owner,
            Map.of("pathPrefix", "", "scopeSubject", subject, "effect", "DENY"), Map.class);
        post("/api/v1/vaults/" + vaultId + "/path-rules", owner,
            Map.of("pathPrefix", pathPrefix, "scopeSubject", subject, "effect", "ALLOW"), Map.class);
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

    /** Vault-Id -> Ersteller, damit Helfer wie {@link #grantReadOnlyOnPath} mit dessen Rechten arbeiten koennen. */
    private final Map<String, String> vaultOwners = new ConcurrentHashMap<>();

    private String createVault(String actor, String name) throws Exception {
        var vault = post("/api/v1/vaults", bearerAuth(actor), Map.of("name", name), Map.class);
        var vaultId = (String) vault.get("id");
        vaultOwners.put(vaultId, actor);
        return vaultId;
    }

    private String createNote(String actor, String vaultId, String path) throws Exception {
        var created = post(
            "/api/v1/vaults/" + vaultId + "/notes", bearerAuth(actor), Map.of("path", path, "noteLevel", 1), Map.class);
        return (String) created.get("id");
    }

    /** {@code [1 Byte Typ][36 Byte NoteId als ASCII-UUID][Rest: Payload]} - muss zu SyncFrame.java passen. */
    private static ByteBuffer frame(byte type, String noteId, byte[] payload) {
        var noteIdBytes = noteId.getBytes(StandardCharsets.US_ASCII);
        var buffer = ByteBuffer.allocate(1 + noteIdBytes.length + payload.length);
        buffer.put(type).put(noteIdBytes).put(payload);
        buffer.flip();
        return buffer;
    }

    private record ReceivedFrame(String noteId, byte type, byte[] payload) {
    }

    /**
     * Sammelt eingehende Frames PRO NoteId - eine geteilte Verbindung joint viele Raeume
     * gleichzeitig, die Reihenfolge ZWISCHEN verschiedenen Notizen ist nicht garantiert (wohl
     * aber innerhalb einer einzelnen Notiz, dank TCP-Reihenfolge + sequentieller
     * Server-Verarbeitung pro Session).
     */
    private static final class CapturingHandler extends BinaryWebSocketHandler {
        final Map<String, BlockingQueue<ReceivedFrame>> byNote = new ConcurrentHashMap<>();
        final CountDownLatch closedLatch = new CountDownLatch(1);
        volatile CloseStatus closeStatus;

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            var raw = new byte[message.getPayloadLength()];
            message.getPayload().get(raw);
            var type = raw[0];
            var noteId = new String(raw, 1, NOTE_ID_LENGTH, StandardCharsets.US_ASCII);
            var payload = Arrays.copyOfRange(raw, 1 + NOTE_ID_LENGTH, raw.length);
            byNote.computeIfAbsent(noteId, id -> new LinkedBlockingQueue<>()).add(new ReceivedFrame(noteId, type, payload));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            this.closeStatus = status;
            closedLatch.countDown();
        }

        ReceivedFrame awaitFrame(String noteId, long timeoutSeconds) throws InterruptedException {
            var queue = byNote.computeIfAbsent(noteId, id -> new LinkedBlockingQueue<>());
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (true) {
                var remainingNanos = deadline - System.nanoTime();
                var frame = queue.poll(Math.max(0, remainingNanos), TimeUnit.NANOSECONDS);
                assertThat(frame).as("expected a frame for note %s within %ds", noteId, timeoutSeconds).isNotNull();
                if (frame.type() != MESSAGE_TYPE_CATCHUP_COMPLETE) {
                    return frame;
                }
            }
        }
    }

    /** Ein simuliertes Endgeraet: eigener Actor, eigene WS-Verbindung(en), eigener HTTP-Auth-Header. */
    private final class Device {
        final String actor;
        WebSocketSession session;
        CapturingHandler handler;

        Device(String actor) {
            this.actor = actor;
        }

        Map<String, String> auth() {
            return bearerAuth(actor);
        }

        String issueVaultTicket(String vaultId) throws Exception {
            var response = post("/api/v1/vaults/" + vaultId + "/sync-tickets", auth(), null, Map.class);
            return (String) response.get("token");
        }

        /** Frische TCP-Verbindung MIT frischem Ticket - fuer den initialen Connect UND jeden Reconnect. */
        void connect(String vaultId) throws Exception {
            var ticket = issueVaultTicket(vaultId);
            handler = new CapturingHandler();
            session = wsClient.execute(handler, "ws://localhost:" + port + "/ws/sync?ticket=" + ticket)
                .get(5, TimeUnit.SECONDS);
        }

        void join(String noteId) throws Exception {
            session.sendMessage(new BinaryMessage(frame(MESSAGE_TYPE_JOIN, noteId, new byte[0])));
        }

        void leave(String noteId) throws Exception {
            session.sendMessage(new BinaryMessage(frame(MESSAGE_TYPE_LEAVE, noteId, new byte[0])));
        }

        /** Joint die Notiz und schreibt ihren "vollen Inhalt" als ein einzelnes Dokument-Update. */
        void writeNote(String noteId, byte[] content) throws Exception {
            join(noteId);
            session.sendMessage(new BinaryMessage(frame(MESSAGE_TYPE_DOC_UPDATE, noteId, content)));
        }

        ReceivedFrame awaitCatchup(String noteId) throws InterruptedException {
            return handler.awaitFrame(noteId, 30);
        }

        /** Simuliert einen Verbindungsabriss (Netzwechsel, App im Hintergrund, ...). */
        void disconnectAbruptly() throws Exception {
            session.close();
        }
    }

    /**
     * Der Kern der sofortigen Bestandssynchronisierung: Geraet B hat die betroffene Notiz NICHT
     * gejoint (seit das Plugin nur noch geoeffnete Notizen joint, ist das der Normalfall) und muss
     * Anlage, Umbenennung und Loeschung trotzdem sofort erfahren - frueher erfuhr es davon
     * ueberhaupt nichts und entdeckte die Aenderung erst beim naechsten vollstaendigen Abgleich.
     */
    @Test
    void should_announceNoteCreationToEveryDeviceOfTheVault_evenWhenTheyHaveNotJoinedThatNote() throws Exception {
        var vaultId = createVault("ivan", "vault-announcements");
        var deviceB = new Device("ivan");
        deviceB.connect(vaultId);

        var noteId = createNote("ivan", vaultId, "Ordner/Frisch angelegt.md");

        var announcement = deviceB.handler.awaitFrame(noteId, 15);
        assertThat(announcement.type()).isEqualTo(MESSAGE_TYPE_VAULT_NOTE_CREATED);
        assertThat(new String(announcement.payload(), StandardCharsets.UTF_8)).isEqualTo("Ordner/Frisch angelegt.md");
    }

    @Test
    void should_announceNoteDeletionToADeviceThatNeverJoinedTheNote() throws Exception {
        var vaultId = createVault("judy", "vault-announcements-delete");
        var noteId = createNote("judy", vaultId, "Verschwindet.md");

        var deviceB = new Device("judy");
        deviceB.connect(vaultId);

        var deleteHeaders = new HashMap<>(bearerAuth("judy"));
        deleteHeaders.put("X-Operation-Id", "sim-vault-announce-delete");
        assertThat(delete("/api/v1/vaults/" + vaultId + "/notes/" + noteId, deleteHeaders).statusCode()).isEqualTo(200);

        var announcement = deviceB.handler.awaitFrame(noteId, 15);
        assertThat(announcement.type()).isEqualTo(MESSAGE_TYPE_VAULT_NOTE_DELETED);
        assertThat(new String(announcement.payload(), StandardCharsets.UTF_8)).isEqualTo("Verschwindet.md");
        assertThat(deviceB.session.isOpen()).as("eine Bestandsankuendigung trennt die Verbindung nicht").isTrue();
    }

    @Test
    void should_announceNoteRenameWithTheNewPath_toADeviceThatNeverJoinedTheNote() throws Exception {
        var vaultId = createVault("karl", "vault-announcements-rename");
        var noteId = createNote("karl", vaultId, "Alt.md");

        var deviceB = new Device("karl");
        deviceB.connect(vaultId);

        assertThat(patch("/api/v1/vaults/" + vaultId + "/notes/" + noteId, bearerAuth("karl"),
            Map.of("path", "Ordner/Neu.md")).statusCode()).isEqualTo(200);

        var announcement = deviceB.handler.awaitFrame(noteId, 15);
        assertThat(announcement.type()).isEqualTo(MESSAGE_TYPE_VAULT_NOTE_RENAMED);
        assertThat(new String(announcement.payload(), StandardCharsets.UTF_8)).isEqualTo("Ordner/Neu.md");
    }

    @Test
    void should_notAnnounceANotePath_toADeviceWithoutReadPermissionOnIt() throws Exception {
        // Sonst waere die Ankuendigung selbst ein Informationsleck ueber Existenz und Ablage
        // fremder Notizen - unabhaengig davon, dass der Inhalt geschuetzt bleibt.
        var vaultId = createVault("lena", "vault-announcements-acl");
        grantReadOnlyOnPath(vaultId, "mallory", "Erlaubt/");

        var outsider = new Device("mallory");
        outsider.connect(vaultId);

        var secretNoteId = createNote("lena", vaultId, "Geheim/Verschlusssache.md");
        var allowedNoteId = createNote("lena", vaultId, "Erlaubt/Sichtbar.md");

        // Die erlaubte Notiz kommt an - das beweist zugleich, dass ueberhaupt zugestellt wird und
        // der fehlende Frame oben nicht bloss ein Timing-Artefakt ist.
        var allowed = outsider.handler.awaitFrame(allowedNoteId, 15);
        assertThat(allowed.type()).isEqualTo(MESSAGE_TYPE_VAULT_NOTE_CREATED);
        assertThat(outsider.handler.byNote.get(secretNoteId))
            .as("keine Ankuendigung fuer eine Notiz ausserhalb der erlaubten Pfade")
            .isNullOrEmpty();
    }

    @Test
    void should_downloadEntireVaultWithCorrectContent_when_deviceBStartsEmpty() throws Exception {
        final int noteCount = 120; // > pageSize(100) - erzwingt mehrseitige Reconciliation
        var vaultId = createVault("alice", "full-vault-transfer");

        var deviceA = new Device("alice");
        deviceA.connect(vaultId);

        var expectedContent = new HashMap<String, byte[]>();
        for (int i = 0; i < noteCount; i++) {
            var path = "Notiz " + i + ".md";
            var noteId = createNote("alice", vaultId, path);
            var content = ("Inhalt von " + path).getBytes(StandardCharsets.UTF_8);
            expectedContent.put(noteId, content);
            deviceA.writeNote(noteId, content);
        }

        // ---- Device B: komplett leer, muss ALLES ueber Reconciliation + Join herunterladen ----
        var deviceB = new Device("alice");
        deviceB.connect(vaultId);

        var allNotes = new ArrayList<Map<String, Object>>();
        String cursor = null;
        String epochId = null;
        while (true) {
            var query = cursor == null ? "" : "&cursor=" + cursor;
            @SuppressWarnings("unchecked")
            var page = json.readValue(
                get("/api/v1/vaults/" + vaultId + "/notes?pageSize=100" + query, deviceB.auth()).body(), Map.class);
            if (epochId == null) {
                epochId = (String) page.get("epochId");
            } else {
                assertThat(page.get("epochId")).as("epochId muss ueber alle Seiten stabil bleiben").isEqualTo(epochId);
            }
            @SuppressWarnings("unchecked")
            var pageNotes = (List<Map<String, Object>>) page.get("notes");
            allNotes.addAll(pageNotes);
            cursor = (String) page.get("nextCursor");
            if (Boolean.TRUE.equals(page.get("complete"))) {
                break;
            }
            assertThat(cursor).as("unvollstaendige Seite muss einen Cursor fuer die naechste liefern").isNotNull();
        }

        assertThat(allNotes).hasSize(noteCount);

        // Ungedrosselter Burst - genau das, was ohne Client-seitige Staffelung (s. Plugin
        // MultiplexedTransport) passieren wuerde. Der Server muss das trotzdem sauber verarbeiten.
        for (var note : allNotes) {
            deviceB.join((String) note.get("id"));
        }
        for (var note : allNotes) {
            var noteId = (String) note.get("id");
            var catchup = deviceB.awaitCatchup(noteId);
            assertThat(catchup.payload()).as("Inhalt von Notiz %s", note.get("path")).isEqualTo(expectedContent.get(noteId));
        }
    }

    @Test
    void should_correctlyTransferUnusuallyLargeNoteContent_withoutTruncationOrCorruption() throws Exception {
        // Isoliert von der Burst-Groesse (Szenario oben): eine einzelne, ungewoehnlich grosse
        // Notiz (600 KB - deutlich ueber dem alten 8-KiB-Tomcat-Default) zusammen mit ein paar
        // normalen, um sicherzustellen, dass grosse UND kleine Notizen auf derselben Verbindung
        // sauber nebeneinander funktionieren.
        var vaultId = createVault("erin", "large-note-transfer");
        var deviceA = new Device("erin");
        deviceA.connect(vaultId);

        var smallNoteId = createNote("erin", vaultId, "Klein.md");
        var largeNoteId = createNote("erin", vaultId, "Gross.md");
        var smallContent = "kleiner Inhalt".getBytes(StandardCharsets.UTF_8);
        var largeContent = ("GROSSE-NOTIZ-" + "X".repeat(600_000)).getBytes(StandardCharsets.UTF_8);
        deviceA.writeNote(smallNoteId, smallContent);
        deviceA.writeNote(largeNoteId, largeContent);

        var deviceB = new Device("erin");
        deviceB.connect(vaultId);
        deviceB.join(smallNoteId);
        deviceB.join(largeNoteId);

        assertThat(deviceB.awaitCatchup(smallNoteId).payload()).isEqualTo(smallContent);
        assertThat(deviceB.awaitCatchup(largeNoteId).payload()).isEqualTo(largeContent);
    }

    @Test
    void should_catchUpEveryNote_underAnUnthrottledBurstOfManySimultaneousJoins() throws Exception {
        // Stresst gezielt NUR die Nebenlaeufigkeit (viele Joins praktisch gleichzeitig ueber
        // dieselbe Verbindung), mit kleinem Inhalt - isoliert von grossen Payloads. Deckt genau
        // den Fund ab: WebSocketSession.sendMessage() ist nicht threadsicher, ein Catchup- und
        // ein Broadcast-Push auf dieselbe Session konnten sich gegenseitig ueberschreiben/
        // verlieren, wenn viele Joins (und damit Catchups) praktisch gleichzeitig verarbeitet
        // wurden.
        final int noteCount = 150;
        var vaultId = createVault("frank", "bursty-join-stress");
        var deviceA = new Device("frank");
        deviceA.connect(vaultId);

        var expectedContent = new HashMap<String, byte[]>();
        var noteIds = new ArrayList<String>();
        for (int i = 0; i < noteCount; i++) {
            var noteId = createNote("frank", vaultId, "Notiz " + i + ".md");
            var content = ("Inhalt " + i).getBytes(StandardCharsets.UTF_8);
            expectedContent.put(noteId, content);
            noteIds.add(noteId);
            deviceA.writeNote(noteId, content);
        }

        var deviceB = new Device("frank");
        deviceB.connect(vaultId);
        for (var noteId : noteIds) {
            deviceB.join(noteId);
        }
        for (var noteId : noteIds) {
            assertThat(deviceB.awaitCatchup(noteId).payload())
                .as("Inhalt von Notiz %s (Burst aus %d gleichzeitigen Joins)", noteId, noteCount)
                .isEqualTo(expectedContent.get(noteId));
        }
    }

    @Test
    void should_completeFullTransfer_afterConnectionDropsPartwayThroughAndDeviceReconnects() throws Exception {
        final int noteCount = 60;
        var vaultId = createVault("bob", "disconnect-mid-transfer");
        var deviceA = new Device("bob");
        deviceA.connect(vaultId);

        var expectedContent = new HashMap<String, byte[]>();
        var noteIds = new ArrayList<String>();
        for (int i = 0; i < noteCount; i++) {
            var noteId = createNote("bob", vaultId, "Notiz " + i + ".md");
            var content = ("Inhalt " + i).getBytes(StandardCharsets.UTF_8);
            expectedContent.put(noteId, content);
            noteIds.add(noteId);
            deviceA.writeNote(noteId, content);
        }

        var deviceB = new Device("bob");
        deviceB.connect(vaultId);

        // Erste Haelfte joinen und Catchup abwarten - dann die Verbindung hart abbrechen, so wie
        // es dem Plugin auf Mobile live passiert ist (Netzwerkwechsel, App im Hintergrund, ...).
        var firstHalf = noteIds.subList(0, noteCount / 2);
        var secondHalf = noteIds.subList(noteCount / 2, noteCount);
        for (var noteId : firstHalf) {
            deviceB.join(noteId);
        }
        for (var noteId : firstHalf) {
            assertThat(deviceB.awaitCatchup(noteId).payload()).isEqualTo(expectedContent.get(noteId));
        }

        deviceB.disconnectAbruptly();

        // Reconnect mit FRISCHEM Ticket (Regression: ein wiederverwendetes Ticket waere schon
        // verbraucht und wuerde mit 403 scheitern, s. Plugin-Fix 0.8.2) - dann die zweite Haelfte nachholen.
        deviceB.connect(vaultId);
        for (var noteId : secondHalf) {
            deviceB.join(noteId);
        }
        for (var noteId : secondHalf) {
            assertThat(deviceB.awaitCatchup(noteId).payload()).isEqualTo(expectedContent.get(noteId));
        }
    }

    @Test
    void should_excludeDeletedNote_fromReconciliation_when_deletedBeforeDeviceBJoinsIt() throws Exception {
        var vaultId = createVault("carol", "deletion-during-transfer");
        var deviceA = new Device("carol");
        deviceA.connect(vaultId);

        var keptNoteId = createNote("carol", vaultId, "Bleibt.md");
        var deletedNoteId = createNote("carol", vaultId, "Wird geloescht.md");
        deviceA.writeNote(keptNoteId, "bleibt".getBytes(StandardCharsets.UTF_8));
        deviceA.writeNote(deletedNoteId, "wird geloescht".getBytes(StandardCharsets.UTF_8));

        // Geloescht BEVOR Device B ueberhaupt reconciled - Device B darf davon nie etwas sehen.
        var deleteHeaders = new HashMap<>(deviceA.auth());
        deleteHeaders.put("X-Operation-Id", "sim-delete-1");
        assertThat(delete("/api/v1/vaults/" + vaultId + "/notes/" + deletedNoteId, deleteHeaders).statusCode()).isEqualTo(200);

        var deviceB = new Device("carol");
        deviceB.connect(vaultId);
        @SuppressWarnings("unchecked")
        var page = json.readValue(get("/api/v1/vaults/" + vaultId + "/notes?pageSize=100", deviceB.auth()).body(), Map.class);
        @SuppressWarnings("unchecked")
        var notes = (List<Map<String, Object>>) page.get("notes");

        assertThat(notes).extracting(n -> n.get("id")).containsExactly(keptNoteId);

        deviceB.join(keptNoteId);
        assertThat(deviceB.awaitCatchup(keptNoteId).payload()).isEqualTo("bleibt".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void should_rejectTicketIssuance_forDeviceWithoutAnyPermissionInVault() throws Exception {
        var vaultId = createVault("dave", "no-permission-vault");

        var response = postRaw("/api/v1/vaults/" + vaultId + "/sync-tickets", bearerAuth("mallory"), null);

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void should_notDisruptSiblingNote_when_oneNoteOnTheSameSharedConnectionIsDeleted() throws Exception {
        // Regression: seit der Multiplexing-Umstellung teilen sich viele Notizen EINE
        // Verbindung. Die Loeschung einer Notiz darf die Verbindung nicht kappen und damit alle
        // anderen, ueber dieselbe Verbindung laufenden Notizen mitreissen (live per Simulation
        // gefunden - der urspruengliche Code schloss bei Loeschung die GESAMTE Session).
        var vaultId = createVault("heidi", "deletion-does-not-kill-connection");
        var deviceA = new Device("heidi");
        deviceA.connect(vaultId);

        var survivingNoteId = createNote("heidi", vaultId, "Ueberlebt.md");
        var deletedNoteId = createNote("heidi", vaultId, "Wird geloescht.md");
        deviceA.writeNote(survivingNoteId, "ueberlebt-v1".getBytes(StandardCharsets.UTF_8));
        deviceA.writeNote(deletedNoteId, "wird geloescht".getBytes(StandardCharsets.UTF_8));

        var deviceB = new Device("heidi");
        deviceB.connect(vaultId);
        deviceB.join(survivingNoteId);
        deviceB.join(deletedNoteId);
        deviceB.awaitCatchup(survivingNoteId);
        deviceB.awaitCatchup(deletedNoteId);

        var deleteHeaders = new HashMap<>(deviceA.auth());
        deleteHeaders.put("X-Operation-Id", "sim-delete-sibling");
        assertThat(delete("/api/v1/vaults/" + vaultId + "/notes/" + deletedNoteId, deleteHeaders).statusCode()).isEqualTo(200);

        var deletionNotice = deviceB.awaitCatchup(deletedNoteId);
        assertThat(deletionNotice.type()).isEqualTo(MESSAGE_TYPE_NOTE_DELETED);
        assertThat(deviceB.session.isOpen()).as("Verbindung bleibt trotz Notiz-Loeschung offen").isTrue();

        // Die ueberlebende Notiz muss auf DERSELBEN Verbindung weiter ganz normal funktionieren.
        deviceA.session.sendMessage(new BinaryMessage(
            frame(MESSAGE_TYPE_DOC_UPDATE, survivingNoteId, "ueberlebt-v2".getBytes(StandardCharsets.UTF_8))));
        assertThat(deviceB.awaitCatchup(survivingNoteId).payload()).isEqualTo("ueberlebt-v2".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void should_broadcastToAllWatchers_when_multipleDevicesEditTheSameNoteConcurrently() throws Exception {
        var vaultId = createVault("ivan", "concurrent-multi-editor");
        var owner = new Device("ivan");
        owner.connect(vaultId);
        var noteId = createNote("ivan", vaultId, "Gemeinsam.md");

        var editorA = new Device("ivan");
        var editorB = new Device("ivan");
        var watcher = new Device("ivan");
        editorA.connect(vaultId);
        editorB.connect(vaultId);
        watcher.connect(vaultId);
        editorA.join(noteId);
        editorB.join(noteId);
        watcher.join(noteId);

        // "Gleichzeitig" (keine Wartezeit dazwischen) senden zwei verschiedene Geraete je ein
        // Update fuer dieselbe Notiz - der Relay ist "dumm" (persistiert/verteilt nur, mischt
        // nicht selbst), das eigentliche CRDT-Merge passiert clientseitig (s. SyncClient.test.ts);
        // hier wird geprueft, dass unter echter Nebenlaeufigkeit BEIDE Updates bei JEDEM anderen
        // Teilnehmer ankommen und in der Server-Historie landen, keins verloren geht.
        editorA.session.sendMessage(new BinaryMessage(
            frame(MESSAGE_TYPE_DOC_UPDATE, noteId, "update-von-a".getBytes(StandardCharsets.UTF_8))));
        editorB.session.sendMessage(new BinaryMessage(
            frame(MESSAGE_TYPE_DOC_UPDATE, noteId, "update-von-b".getBytes(StandardCharsets.UTF_8))));

        var atWatcher = List.of(watcher.awaitCatchup(noteId).payload(), watcher.awaitCatchup(noteId).payload());
        assertThat(atWatcher).containsExactlyInAnyOrder(
            "update-von-a".getBytes(StandardCharsets.UTF_8), "update-von-b".getBytes(StandardCharsets.UTF_8));

        // A bekommt NUR das Update von B (kein Echo des eigenen), und umgekehrt.
        assertThat(editorA.awaitCatchup(noteId).payload()).isEqualTo("update-von-b".getBytes(StandardCharsets.UTF_8));
        assertThat(editorB.awaitCatchup(noteId).payload()).isEqualTo("update-von-a".getBytes(StandardCharsets.UTF_8));

        // Ein spaeter beitretendes drittes Geraet bekommt per Late-Joiner-Catchup BEIDE Updates,
        // die Server-Historie hat also wirklich beide dauerhaft persistiert.
        var lateJoiner = new Device("ivan");
        lateJoiner.connect(vaultId);
        lateJoiner.join(noteId);
        var catchup = List.of(lateJoiner.awaitCatchup(noteId).payload(), lateJoiner.awaitCatchup(noteId).payload());
        assertThat(catchup).containsExactlyInAnyOrder(
            "update-von-a".getBytes(StandardCharsets.UTF_8), "update-von-b".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void should_catchUpOnEverythingMissed_when_deviceWasOfflineAndThenReconnects() throws Exception {
        // "Offline Aenderung, dann wieder online": ein Geraet trennt die Verbindung, WAEHREND es
        // getrennt ist passieren mehrere Aenderungen durch ein anderes Geraet - beim Reconnect
        // muss es per Late-Joiner-Catchup ALLES nachgeliefert bekommen, UND danach selbst wieder
        // ganz normal eigene Aenderungen einspielen koennen.
        var vaultId = createVault("judy", "offline-then-reconnect");
        var noteId = createNote("judy", vaultId, "Reisenotizen.md");

        var mobile = new Device("judy");
        mobile.connect(vaultId);
        mobile.writeNote(noteId, "v1-vor-offline".getBytes(StandardCharsets.UTF_8));

        var desktop = new Device("judy");
        desktop.connect(vaultId);
        desktop.join(noteId);
        assertThat(desktop.awaitCatchup(noteId).payload()).isEqualTo("v1-vor-offline".getBytes(StandardCharsets.UTF_8));

        // Mobile geht "offline" (Verbindungsabbruch) - waehrend der Abwesenheit editiert Desktop
        // mehrfach weiter.
        mobile.disconnectAbruptly();
        desktop.session.sendMessage(new BinaryMessage(
            frame(MESSAGE_TYPE_DOC_UPDATE, noteId, "v2-waehrend-offline".getBytes(StandardCharsets.UTF_8))));
        desktop.session.sendMessage(new BinaryMessage(
            frame(MESSAGE_TYPE_DOC_UPDATE, noteId, "v3-waehrend-offline".getBytes(StandardCharsets.UTF_8))));

        // Mobile kommt wieder online (frisches Ticket, s. Plugin-Fix 0.8.2) und muss BEIDE
        // verpassten Updates per Catchup nachgeliefert bekommen.
        mobile.connect(vaultId);
        mobile.join(noteId);
        // Late-Joiner-Catchup liefert IMMER die komplette Historie ab dem Anfang, nicht nur
        // "seit dem letzten Mal gesehen" (der Relay fuehrt pro Client keinen Offset) - das ist
        // korrekt und beabsichtigt: echte Yjs-Updates sind idempotente CRDT-Operationen, ein
        // erneutes Anwenden eines bereits bekannten Updates aendert am Client-Zustand nichts.
        var missed = List.of(
            mobile.awaitCatchup(noteId).payload(), mobile.awaitCatchup(noteId).payload(), mobile.awaitCatchup(noteId).payload());
        assertThat(missed).containsExactly(
            "v1-vor-offline".getBytes(StandardCharsets.UTF_8),
            "v2-waehrend-offline".getBytes(StandardCharsets.UTF_8),
            "v3-waehrend-offline".getBytes(StandardCharsets.UTF_8));

        // Und kann direkt danach wieder ganz normal selbst schreiben - der wieder verbundene
        // Client ist voll funktionsfaehig, nicht nur lesend.
        mobile.session.sendMessage(new BinaryMessage(
            frame(MESSAGE_TYPE_DOC_UPDATE, noteId, "v4-nach-reconnect".getBytes(StandardCharsets.UTF_8))));
        assertThat(desktop.awaitCatchup(noteId).payload()).isEqualTo("v4-nach-reconnect".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void should_completeFullVaultTransfer_forMobileLikeDevice_withStaggeredJoinsAndRepeatedFlakyReconnects() throws Exception {
        // Mimikt das reale Plugin-Verhalten auf Mobile (MultiplexedTransport: gestaffelte Joins
        // statt Burst, s. joinStaggerMs) PLUS ein besonders instabiles Netz (mehrfacher
        // Verbindungsabbruch MITTEN im Transfer, nicht nur einmal wie im Desktop-Szenario oben).
        final int noteCount = 40;
        final int staggerMs = 20;
        var vaultId = createVault("gina", "mobile-like-device");
        var deviceA = new Device("gina");
        deviceA.connect(vaultId);

        var expectedContent = new HashMap<String, byte[]>();
        var noteIds = new ArrayList<String>();
        for (int i = 0; i < noteCount; i++) {
            var noteId = createNote("gina", vaultId, "Notiz " + i + ".md");
            var content = ("Mobile-Inhalt " + i).getBytes(StandardCharsets.UTF_8);
            expectedContent.put(noteId, content);
            noteIds.add(noteId);
            deviceA.writeNote(noteId, content);
        }

        var mobile = new Device("gina");
        mobile.connect(vaultId);

        var confirmed = new HashMap<String, byte[]>();
        var reconnectCount = 0;

        for (int i = 0; i < noteIds.size(); i++) {
            mobile.join(noteIds.get(i));
            Thread.sleep(staggerMs);

            // Alle 13 Notizen (bewusst kein glatter Teiler von noteCount) bricht die
            // "Mobilverbindung" hart ab, wie im echten Netz beobachtet - danach MUESSEN alle noch
            // unbestaetigten Notizen auf der neuen Verbindung neu gejoint werden.
            if ((i + 1) % 13 == 0) {
                mobile.disconnectAbruptly();
                reconnectCount++;
                mobile.connect(vaultId);
                for (var noteId : noteIds.subList(0, i + 1)) {
                    if (!confirmed.containsKey(noteId)) {
                        mobile.join(noteId);
                    }
                }
            }

            for (var noteId : noteIds.subList(0, i + 1)) {
                if (!confirmed.containsKey(noteId)) {
                    var queue = mobile.handler.byNote.get(noteId);
                    var f = queue == null ? null : queue.poll();
                    if (f != null) {
                        confirmed.put(noteId, f.payload());
                    }
                }
            }
        }

        // Letzte Aufraeumrunde: alles, was bis hierhin noch nicht bestaetigt ist, mit
        // grosszuegigem Timeout final abwarten (bereits gejoint, Catchup evtl. noch unterwegs).
        for (var noteId : noteIds) {
            confirmed.computeIfAbsent(noteId, id -> {
                try {
                    return mobile.awaitCatchup(id).payload();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        assertThat(reconnectCount).as("Testszenario muss mehrfach die Verbindung abbrechen").isGreaterThanOrEqualTo(3);
        for (var noteId : noteIds) {
            assertThat(confirmed.get(noteId)).as("Inhalt von Notiz %s nach flakyem Mobilnetz", noteId)
                .isEqualTo(expectedContent.get(noteId));
        }
    }
}
