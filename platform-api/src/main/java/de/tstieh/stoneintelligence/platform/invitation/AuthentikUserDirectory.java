package de.tstieh.stoneintelligence.platform.invitation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * {@link UserDirectory} ueber die Authentik-API v3 mit einem Service-Account-Token, der nur Nutzer
 * lesen und Einladungen anlegen/loeschen darf.
 *
 * <p>Die Suche beschraenkt sich auf aktive, interne Konten der konfigurierten Gruppe (bei tstieh:
 * "User", also freigeschaltete Konten dieser Instanz) - Konten anderer Brands auf derselben
 * Authentik-Instanz tauchen darin nie auf.
 */
public class AuthentikUserDirectory implements UserDirectory {

    private final RestClient http;
    private final String baseUrl;
    private final String invitationFlowSlug;
    private final String directoryGroup;
    private volatile String invitationFlowPk;

    public AuthentikUserDirectory(String baseUrl, String apiToken, String invitationFlowSlug, String directoryGroup) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.invitationFlowSlug = invitationFlowSlug;
        this.directoryGroup = directoryGroup;
        this.http = RestClient.builder()
            .baseUrl(this.baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiToken)
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<DirectoryUser> search(String query, int limit) {
        return users(Map.of("search", query, "groups_by_name", directoryGroup, "page_size", String.valueOf(limit)));
    }

    @Override
    public Optional<DirectoryUser> findByEmail(String email) {
        return users(Map.of("email", email, "page_size", "2")).stream()
            .filter(user -> email.equalsIgnoreCase(user.email())).findFirst();
    }

    @Override
    public Optional<DirectoryUser> findByUsername(String username) {
        return users(Map.of("username", username, "page_size", "2")).stream()
            .filter(user -> username.equals(user.username())).findFirst();
    }

    /**
     * Filterwerte gehen als URI-Variablen hinein, nicht als woertliche Query-Werte: nur so werden
     * sie strikt kodiert - ein "+" in einer E-Mail-Adresse kaeme sonst beim Server als Leerzeichen an.
     */
    private List<DirectoryUser> users(Map<String, String> filters) {
        var params = new java.util.LinkedHashMap<String, String>(filters);
        params.put("is_active", "true");
        params.put("type", "internal");
        var response = http.get()
            .uri(uri -> {
                var builder = uri.path("/api/v3/core/users/");
                params.keySet().forEach(key -> builder.queryParam(key, "{" + key + "}"));
                return builder.build(params);
            })
            .retrieve()
            .body(JsonNode.class);
        if (response == null || !response.has("results")) {
            return List.of();
        }
        var users = new java.util.ArrayList<DirectoryUser>();
        for (var node : response.get("results")) {
            users.add(new DirectoryUser(node.path("username").asText(), node.path("name").asText(node.path("username").asText()),
                node.path("email").asText("")));
        }
        return users;
    }

    @Override
    public ExternalInvitation createInvitation(String email, Instant expiresAt) {
        var created = http.post()
            .uri("/api/v3/stages/invitation/invitations/")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of(
                "name", "stoneintelligence-" + UUID.randomUUID().toString().substring(0, 8),
                "expires", expiresAt.toString(),
                "single_use", true,
                "flow", invitationFlowPk(),
                "fixed_data", Map.of("email", email)))
            .retrieve()
            .body(JsonNode.class);
        var pk = created == null ? "" : created.path("pk").asText();
        if (pk.isBlank()) {
            throw new IllegalStateException("Authentik lieferte keine Einladungs-Id");
        }
        return new ExternalInvitation(pk, baseUrl + "/if/flow/" + invitationFlowSlug + "/?itoken=" + pk);
    }

    @Override
    public void deleteInvitation(String id) {
        http.delete().uri("/api/v3/stages/invitation/invitations/{id}/", id).retrieve().toBodilessEntity();
    }

    private String invitationFlowPk() {
        var cached = invitationFlowPk;
        if (cached != null) {
            return cached;
        }
        var flow = http.get().uri("/api/v3/flows/instances/{slug}/", invitationFlowSlug).retrieve().body(JsonNode.class);
        var pk = flow == null ? "" : flow.path("pk").asText();
        if (pk.isBlank()) {
            throw new IllegalStateException("Einladungs-Flow '" + invitationFlowSlug + "' nicht gefunden");
        }
        invitationFlowPk = pk;
        return pk;
    }
}
