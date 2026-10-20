package de.tstieh.stoneintelligence.platform.scim;

import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * SCIM 2.0 (RFC 7643/7644) fuer Entra ID, Okta, Authentik & Co. (ADR 0013): Konten und Gruppen
 * anlegen, ersetzen, aendern, loeschen und filtern; dazu die Discovery-Endpunkte. Keine Bulk-
 * Operationen, kein Sortieren, kein {@code /Me} - das bietet {@link #serviceProviderConfig} auch nicht an.
 */
@RestController
@RequestMapping(path = "/scim/v2", produces = {ScimController.SCIM_JSON, MediaType.APPLICATION_JSON_VALUE})
public class ScimController {

    static final String SCIM_JSON = "application/scim+json";
    static final int DEFAULT_COUNT = 100;
    static final int MAX_COUNT = 1000;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ScimService scim;

    public ScimController(ScimService scim) {
        this.scim = scim;
    }

    // --- Users ----------------------------------------------------------------------------------

    @PostMapping("/Users")
    public ResponseEntity<JsonNode> createUser(@RequestBody JsonNode body, HttpServletRequest request) {
        var user = ScimResources.user(scim.createUser(body), baseUrl(request));
        return created(user);
    }

    @GetMapping("/Users/{id}")
    public ResponseEntity<JsonNode> user(@PathVariable UUID id, HttpServletRequest request,
                                         @RequestParam(required = false) String attributes,
                                         @RequestParam(required = false) String excludedAttributes) {
        return ok(ScimResources.project(ScimResources.user(scim.user(id), baseUrl(request)), attributes, excludedAttributes));
    }

    @GetMapping("/Users")
    public ResponseEntity<JsonNode> users(@RequestParam(required = false) String filter,
                                          @RequestParam(required = false) Integer startIndex,
                                          @RequestParam(required = false) Integer count,
                                          @RequestParam(required = false) String attributes,
                                          @RequestParam(required = false) String excludedAttributes,
                                          HttpServletRequest request) {
        var start = start(startIndex);
        var page = scim.users(filter, start, count(count));
        var base = baseUrl(request);
        var resources = page.items().stream()
            .map(user -> ScimResources.project(ScimResources.user(user, base), attributes, excludedAttributes)).toList();
        return ok(ScimResources.list(resources, page.total(), start));
    }

    @PutMapping("/Users/{id}")
    public ResponseEntity<JsonNode> replaceUser(@PathVariable UUID id, @RequestBody JsonNode body, HttpServletRequest request) {
        return ok(ScimResources.user(scim.replaceUser(id, body), baseUrl(request)));
    }

    @PatchMapping("/Users/{id}")
    public ResponseEntity<JsonNode> patchUser(@PathVariable UUID id, @RequestBody JsonNode body, HttpServletRequest request) {
        return ok(ScimResources.user(scim.patchUser(id, body), baseUrl(request)));
    }

    @DeleteMapping("/Users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        scim.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // --- Groups ---------------------------------------------------------------------------------

    @PostMapping("/Groups")
    public ResponseEntity<JsonNode> createGroup(@RequestBody JsonNode body, HttpServletRequest request) {
        var group = scim.createGroup(body);
        return created(ScimResources.group(group, scim.members(group.id()), baseUrl(request)));
    }

    @GetMapping("/Groups/{id}")
    public ResponseEntity<JsonNode> group(@PathVariable UUID id, HttpServletRequest request,
                                          @RequestParam(required = false) String attributes,
                                          @RequestParam(required = false) String excludedAttributes) {
        var group = scim.group(id);
        var members = ScimResources.wantsMembers(attributes, excludedAttributes) ? scim.members(id) : null;
        return ok(ScimResources.project(ScimResources.group(group, members, baseUrl(request)), attributes, excludedAttributes));
    }

    @GetMapping("/Groups")
    public ResponseEntity<JsonNode> groups(@RequestParam(required = false) String filter,
                                           @RequestParam(required = false) Integer startIndex,
                                           @RequestParam(required = false) Integer count,
                                           @RequestParam(required = false) String attributes,
                                           @RequestParam(required = false) String excludedAttributes,
                                           HttpServletRequest request) {
        var start = start(startIndex);
        var page = scim.groups(filter, start, count(count));
        var base = baseUrl(request);
        var withMembers = ScimResources.wantsMembers(attributes, excludedAttributes);
        var resources = page.items().stream()
            .map(group -> ScimResources.project(
                ScimResources.group(group, withMembers ? scim.members(group.id()) : null, base), attributes, excludedAttributes))
            .toList();
        return ok(ScimResources.list(resources, page.total(), start));
    }

    @PutMapping("/Groups/{id}")
    public ResponseEntity<JsonNode> replaceGroup(@PathVariable UUID id, @RequestBody JsonNode body, HttpServletRequest request) {
        var group = scim.replaceGroup(id, body);
        return ok(ScimResources.group(group, scim.members(id), baseUrl(request)));
    }

    /** 204 wie es Entra ID erwartet; Mitglieder einer grossen Gruppe werden dafuer nicht geladen. */
    @PatchMapping("/Groups/{id}")
    public ResponseEntity<Void> patchGroup(@PathVariable UUID id, @RequestBody JsonNode body) {
        scim.patchGroup(id, body);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/Groups/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable UUID id) {
        scim.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }

    // --- Discovery (RFC 7644 Abschnitt 4) -------------------------------------------------------

    @GetMapping("/ServiceProviderConfig")
    public ResponseEntity<JsonNode> serviceProviderConfig(HttpServletRequest request) {
        var node = JSON.objectNode();
        node.putArray("schemas").add("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig");
        node.put("documentationUri", "https://github.com/Raindancer118/stoneintelligence/blob/main/docs/scim.md");
        node.putObject("patch").put("supported", true);
        node.putObject("bulk").put("supported", false).put("maxOperations", 0).put("maxPayloadSize", 0);
        node.putObject("filter").put("supported", true).put("maxResults", MAX_COUNT);
        node.putObject("changePassword").put("supported", false);
        node.putObject("sort").put("supported", false);
        node.putObject("etag").put("supported", false);
        node.putArray("authenticationSchemes").addObject()
            .put("type", "oauthbearertoken").put("name", "Bearer token").put("primary", true)
            .put("description", "The token from STONEINTELLIGENCE_SCIM_TOKEN");
        node.set("meta", JSON.objectNode().put("resourceType", "ServiceProviderConfig")
            .put("location", baseUrl(request) + "/ServiceProviderConfig"));
        return ok(node);
    }

    @GetMapping("/ResourceTypes")
    public ResponseEntity<JsonNode> resourceTypes(HttpServletRequest request) {
        var base = baseUrl(request);
        return ok(ScimResources.list(List.of(resourceType("User", "/Users", ScimResources.USER_SCHEMA, base),
            resourceType("Group", "/Groups", ScimResources.GROUP_SCHEMA, base)), 2, 1));
    }

    @GetMapping("/Schemas")
    public ResponseEntity<JsonNode> schemas() {
        return ok(ScimResources.list(List.of(ScimSchemas.user(), ScimSchemas.group()), 2, 1));
    }

    @GetMapping("/Schemas/{id}")
    public ResponseEntity<JsonNode> schema(@PathVariable String id) {
        if (id.equals(ScimResources.USER_SCHEMA)) {
            return ok(ScimSchemas.user());
        }
        if (id.equals(ScimResources.GROUP_SCHEMA)) {
            return ok(ScimSchemas.group());
        }
        throw ScimException.notFound("Schema");
    }

    private static ObjectNode resourceType(String name, String endpoint, String schema, String base) {
        var node = JSON.objectNode();
        node.putArray("schemas").add("urn:ietf:params:scim:schemas:core:2.0:ResourceType");
        node.put("id", name).put("name", name).put("endpoint", endpoint).put("schema", schema);
        node.set("meta", JSON.objectNode().put("resourceType", "ResourceType").put("location", base + "/ResourceTypes/" + name));
        return node;
    }

    // --- Fehler (RFC 7644 Abschnitt 3.12) -------------------------------------------------------

    @ExceptionHandler(ScimException.class)
    public ResponseEntity<JsonNode> scimError(ScimException error) {
        return ResponseEntity.status(error.status()).contentType(MediaType.parseMediaType(SCIM_JSON))
            .body(ScimResources.error(error));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<JsonNode> unreadable(HttpMessageNotReadableException error) {
        return scimError(ScimException.invalidSyntax("the request body is not valid JSON"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<JsonNode> mediaType(HttpMediaTypeNotSupportedException error) {
        return scimError(new ScimException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, null, "use application/scim+json"));
    }

    /** Eine ID, die keine UUID ist, kann es nicht geben. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<JsonNode> badId(MethodArgumentTypeMismatchException error) {
        return scimError(ScimException.notFound("Resource"));
    }

    private static ResponseEntity<JsonNode> ok(JsonNode body) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(SCIM_JSON)).body(body);
    }

    private static ResponseEntity<JsonNode> created(ObjectNode body) {
        var location = body.path("meta").path("location").asString(null);
        var builder = ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.parseMediaType(SCIM_JSON));
        if (location != null) {
            builder.header("Location", location);
        }
        return builder.body(body);
    }

    private static int start(Integer startIndex) {
        return startIndex == null || startIndex < 1 ? 1 : startIndex;
    }

    private static int count(Integer count) {
        return count == null ? DEFAULT_COUNT : Math.max(0, Math.min(count, MAX_COUNT));
    }

    /** Hinter dem Reverse-Proxy: Spring wertet {@code X-Forwarded-*} aus ({@code server.forward-headers-strategy}). */
    private static String baseUrl(HttpServletRequest request) {
        return org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromContextPath(request)
            .path("/scim/v2").build().toUriString();
    }
}
