package de.tstieh.stoneintelligence.platform.scim;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import de.tstieh.stoneintelligence.platform.security.TestJwtSupport;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0013 Schritt 5 gegen die echte Anwendung (HTTP, WebSocket, Postgres): eine Gruppe mit dem IdP
 * verknuepfen, ueber das Token Mitglied werden, per SCIM deaktiviert werden - und sofort alles verlieren.
 */
@Testcontainers
@Import(TestJwtSupport.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScimEndToEndIT {

    private static final String SCIM_TOKEN = "scim-test-token-0123456789-abcdefghijklmnop";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(de.tstieh.stoneintelligence.platform.TestImages.POSTGRES)
            .withDatabaseName("stoneintelligence")
            .withUsername("stoneintelligence")
            .withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("STONEINTELLIGENCE_SCIM_TOKEN", () -> SCIM_TOKEN);
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private static final String TOM = TestJwtSupport.signedJwtFor("tom");
    private static final String BEN = TestJwtSupport.signedJwtWith("ben",
        Map.of("name", "Ben Becker", "groups", List.of("all-employees", "chess-club")));

    private HttpResponse<String> call(String method, String path, String bearer, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            builder.header("Content-Type", path.startsWith("/scim") ? "application/scim+json" : "application/json");
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s -> %s", response.uri(), response.body()).isBetween(200, 299);
        return response.body().isEmpty() ? null : json.readTree(response.body());
    }

    private static final class ClosingHandler extends BinaryWebSocketHandler {
        final CountDownLatch closed = new CountDownLatch(1);
        volatile CloseStatus status;

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            this.status = status;
            closed.countDown();
        }
    }

    @Test
    void should_admitByIdpGroup_andEndEverythingImmediately_whenScimDeactivatesThePerson() throws Exception {
        // SCIM ist nur mit Token offen - und antwortet im SCIM-Format.
        var refused = call("GET", "/scim/v2/Users", null, null);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.body()).contains("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(call("GET", "/scim/v2/Users", TOM, null).statusCode()).as("an OIDC token does not open SCIM").isEqualTo(401);
        assertThat(ok(call("GET", "/scim/v2/ServiceProviderConfig", SCIM_TOKEN, null)).path("patch").path("supported").asBoolean()).isTrue();

        // Tom verknuepft "Alle Beschaeftigten" mit der IdP-Gruppe all-employees.
        var vaultId = ok(call("POST", "/api/v1/vaults", TOM, "{\"name\":\"Firma\"}")).path("id").asString();
        var role = ok(call("POST", "/api/v1/vaults/" + vaultId + "/roles", TOM, "{\"name\":\"lesen\",\"permissions\":[\"READ\"]}")).path("id").asString();
        var group = ok(call("POST", "/api/v1/vaults/" + vaultId + "/groups", TOM, "{\"name\":\"Alle Beschaeftigten\"}")).path("id").asString();
        ok(call("POST", "/api/v1/vaults/" + vaultId + "/groups/" + group + "/roles/" + role, TOM, null));
        ok(call("PUT", "/api/v1/vaults/" + vaultId + "/groups/" + group + "/idp-group", TOM, "{\"idpGroup\":\"all-employees\"}"));

        // Ben wurde nie eingeladen - sein Token genuegt.
        assertThat(ok(call("GET", "/api/v1/vaults", BEN, null)).toString()).contains(vaultId);
        var groups = ok(call("GET", "/api/v1/vaults/" + vaultId + "/groups", TOM, null));
        assertThat(groups.findValues("idpMemberCount").stream().map(JsonNode::asInt)).contains(1);

        var ticket = ok(call("POST", "/api/v1/vaults/" + vaultId + "/sync-tickets", BEN, null)).path("token").asString();
        var socket = new ClosingHandler();
        new StandardWebSocketClient().execute(socket, "ws://localhost:" + port + "/ws/sync?ticket=" + ticket).get(5, TimeUnit.SECONDS);

        // Der IdP uebernimmt Ben per SCIM - Mitglied bleibt er, jetzt ueber die SCIM-Gruppe.
        var ben = ok(call("POST", "/scim/v2/Users", SCIM_TOKEN,
            "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],\"userName\":\"ben\",\"displayName\":\"Ben Becker\"}"));
        var benId = ben.path("id").asString();
        assertThat(ben.path("meta").path("location").asString()).endsWith("/scim/v2/Users/" + benId);
        ok(call("POST", "/scim/v2/Groups", SCIM_TOKEN, "{\"displayName\":\"all-employees\",\"members\":[{\"value\":\"" + benId + "\"}]}"));
        assertThat(call("GET", "/api/v1/vaults/" + vaultId + "/members", BEN, null).statusCode()).isEqualTo(200);
        assertThat(ok(call("GET", "/scim/v2/Users?filter=" + java.net.URLEncoder.encode("userName eq \"BEN\"", java.nio.charset.StandardCharsets.UTF_8),
            SCIM_TOKEN, null)).path("totalResults").asInt()).isEqualTo(1);

        // Deaktivieren wie Entra ID: Zugriff und offene Verbindung enden sofort.
        ok(call("PATCH", "/scim/v2/Users/" + benId, SCIM_TOKEN,
            "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":[{\"op\":\"Replace\",\"path\":\"active\",\"value\":\"False\"}]}"));

        assertThat(socket.closed.await(5, TimeUnit.SECONDS)).as("open sync connection closed").isTrue();
        assertThat(socket.status.getCode()).isEqualTo(SyncWebSocketHandler.CLOSE_ACCOUNT_DEACTIVATED);
        var denied = call("GET", "/api/v1/vaults", BEN, null);
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(value -> assertThat(value).contains("deactivated"));
        assertThat(ok(call("GET", "/api/v1/vaults/" + vaultId + "/audit", TOM, null)).toString()).contains("SCIM_USER_DEACTIVATED");
    }
}
