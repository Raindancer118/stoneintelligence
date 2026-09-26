package de.tstieh.stoneintelligence.platform.invitation;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gegen einen lokalen Nachbau der Authentik-API v3 (nur die benutzten Endpunkte). */
class AuthentikUserDirectoryTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private AuthentikUserDirectory directory;

    @BeforeEach
    void startFakeAuthentik() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        directory = new AuthentikUserDirectory("http://127.0.0.1:" + server.getAddress().getPort(), "secret-token",
            "stoneintelligence-invitation", "User");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        var target = exchange.getRequestMethod() + " " + exchange.getRequestURI();
        requests.add(target + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String response;
        var status = 200;
        if (target.startsWith("GET /api/v3/core/users/")) {
            response = """
                {"pagination":{"count":1},"results":[{"pk":7,"username":"anna","name":"Anna Arendt","email":"anna@example.org","is_active":true}]}
                """;
        } else if (target.startsWith("GET /api/v3/flows/instances/stoneintelligence-invitation/")) {
            response = "{\"pk\":\"flow-uuid\",\"slug\":\"stoneintelligence-invitation\"}";
        } else if (target.startsWith("POST /api/v3/stages/invitation/invitations/")) {
            status = 201;
            response = "{\"pk\":\"inv-uuid\"}";
        } else if (target.startsWith("DELETE /api/v3/stages/invitation/invitations/inv-uuid/")) {
            status = 204;
            response = "";
        } else {
            status = 404;
            response = "{}";
        }
        var bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Test
    void should_searchOnlyActiveInternalAccounts_inTheConfiguredGroup() {
        var results = directory.search("an na", 5);

        assertThat(results).containsExactly(new DirectoryUser("anna", "Anna Arendt", "anna@example.org"));
        assertThat(requests.getFirst())
            .contains("search=an%20na").contains("is_active=true").contains("type=internal")
            .contains("groups_by_name=User").contains("page_size=5").endsWith("auth=Bearer secret-token");
    }

    @Test
    void should_lookUpByExactEmail_andByExactUsername() {
        assertThat(directory.findByEmail("anna@example.org")).isPresent();
        assertThat(requests.getLast()).contains("email=anna%40example.org");

        directory.findByEmail("anna+notizen@example.org");
        assertThat(requests.getLast()).as("'+' muss kodiert ankommen, sonst liest der Server ein Leerzeichen")
            .contains("email=anna%2Bnotizen%40example.org");

        assertThat(directory.findByUsername("anna")).isPresent();
        assertThat(requests.getLast()).contains("username=anna");
    }

    @Test
    void should_createASingleUseInvitation_boundToTheInvitationFlow_withTheEmailFixed() {
        var invitation = directory.createInvitation("neu@example.org", Instant.parse("2026-10-07T10:00:00Z"));

        assertThat(invitation.id()).isEqualTo("inv-uuid");
        assertThat(invitation.enrollmentUrl()).endsWith("/if/flow/stoneintelligence-invitation/?itoken=inv-uuid");
        var body = bodies.getLast();
        assertThat(body).contains("\"single_use\":true").contains("\"flow\":\"flow-uuid\"")
            .contains("\"email\":\"neu@example.org\"").contains("2026-10-07T10:00:00Z");
    }

    @Test
    void should_deleteAnInvitation() {
        directory.deleteInvitation("inv-uuid");

        assertThat(requests.getLast()).startsWith("DELETE /api/v3/stages/invitation/invitations/inv-uuid/");
    }
}
