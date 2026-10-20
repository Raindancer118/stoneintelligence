package de.tstieh.stoneintelligence.platform.scim;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.Account;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.Group;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.Member;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Abbildung zwischen SCIM-JSON (RFC 7643) und {@link DirectoryStore}. */
final class ScimResources {

    static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> ALWAYS_RETURNED = Set.of("id", "schemas", "meta");

    private ScimResources() {
    }

    /** Was aus einem User-JSON gelesen wird; {@code active} fehlt = aktiv (RFC 7643 nennt keinen Default). */
    record UserInput(String userName, String externalId, String displayName, boolean active) {
    }

    record GroupInput(String displayName, String externalId, List<String> memberIds) {
    }

    static UserInput readUser(JsonNode node) {
        requireObject(node);
        var userName = text(node, "userName");
        if (userName == null || userName.isBlank()) {
            throw ScimException.invalidValue("userName is required");
        }
        return new UserInput(userName.strip(), text(node, "externalId"), displayName(node), bool(field(node, "active"), true));
    }

    static GroupInput readGroup(JsonNode node) {
        requireObject(node);
        var displayName = text(node, "displayName");
        if (displayName == null || displayName.isBlank()) {
            throw ScimException.invalidValue("displayName is required");
        }
        return new GroupInput(displayName.strip(), text(node, "externalId"), memberIds(field(node, "members")));
    }

    static ObjectNode user(Account account, String baseUrl) {
        var node = JSON.objectNode();
        node.putArray("schemas").add(USER_SCHEMA);
        node.put("id", account.id().toString());
        if (account.externalId() != null) {
            node.put("externalId", account.externalId());
        }
        node.put("userName", account.userName());
        if (account.displayName() != null) {
            node.put("displayName", account.displayName());
            node.putObject("name").put("formatted", account.displayName());
        }
        node.put("active", account.active());
        node.set("meta", meta("User", account.created(), account.lastModified(), baseUrl + "/Users/" + account.id()));
        return node;
    }

    static ObjectNode group(Group group, List<Member> members, String baseUrl) {
        var node = JSON.objectNode();
        node.putArray("schemas").add(GROUP_SCHEMA);
        node.put("id", group.id().toString());
        if (group.externalId() != null) {
            node.put("externalId", group.externalId());
        }
        node.put("displayName", group.displayName());
        if (members != null) {
            var array = node.putArray("members");
            for (var member : members) {
                var entry = array.addObject().put("value", member.accountId().toString())
                    .put("$ref", baseUrl + "/Users/" + member.accountId()).put("type", "User");
                if (member.displayName() != null) {
                    entry.put("display", member.displayName());
                }
            }
        }
        node.set("meta", meta("Group", group.created(), group.lastModified(), baseUrl + "/Groups/" + group.id()));
        return node;
    }

    static ObjectNode list(List<? extends JsonNode> resources, int total, int startIndex) {
        var node = JSON.objectNode();
        node.putArray("schemas").add(LIST_SCHEMA);
        node.put("totalResults", total);
        node.put("itemsPerPage", resources.size());
        node.put("startIndex", startIndex);
        node.putArray("Resources").addAll(resources);
        return node;
    }

    static ObjectNode error(ScimException error) {
        var node = JSON.objectNode();
        node.putArray("schemas").add(ERROR_SCHEMA);
        node.put("status", String.valueOf(error.status().value()));
        if (error.scimType() != null) {
            node.put("scimType", error.scimType());
        }
        node.put("detail", error.getMessage());
        return node;
    }

    /**
     * {@code attributes}/{@code excludedAttributes} (RFC 7644 Abschnitt 3.4.2.5) auf oberster Ebene;
     * {@code id}, {@code schemas} und {@code meta} kommen immer mit.
     */
    static ObjectNode project(ObjectNode resource, String attributes, String excludedAttributes) {
        if (attributes != null && !attributes.isBlank()) {
            var keep = names(attributes);
            resource.properties().removeIf(entry ->
                !ALWAYS_RETURNED.contains(entry.getKey()) && !keep.contains(entry.getKey().toLowerCase(Locale.ROOT)));
        } else if (excludedAttributes != null && !excludedAttributes.isBlank()) {
            var drop = names(excludedAttributes);
            resource.properties().removeIf(entry ->
                !ALWAYS_RETURNED.contains(entry.getKey()) && drop.contains(entry.getKey().toLowerCase(Locale.ROOT)));
        }
        return resource;
    }

    /** Ob die Antwort Mitglieder braucht - Gruppen mit 100.000 Mitgliedern sollen nicht unnoetig geladen werden. */
    static boolean wantsMembers(String attributes, String excludedAttributes) {
        if (attributes != null && !attributes.isBlank()) {
            return names(attributes).contains("members");
        }
        return excludedAttributes == null || !names(excludedAttributes).contains("members");
    }

    private static Set<String> names(String list) {
        return Arrays.stream(list.split(","))
            .map(String::trim)
            .map(ScimFilter::normalize)
            .map(name -> name.contains(".") ? name.substring(0, name.indexOf('.')) : name)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

    private static ObjectNode meta(String type, Instant created, Instant lastModified, String location) {
        var meta = JSON.objectNode();
        meta.put("resourceType", type);
        meta.put("created", created.toString());
        meta.put("lastModified", lastModified.toString());
        meta.put("location", location);
        meta.put("version", "W/\"" + lastModified.toEpochMilli() + "\"");
        return meta;
    }

    /** Anzeigename: {@code displayName}, sonst {@code name.formatted}, sonst Vor- und Nachname. */
    static String displayName(JsonNode node) {
        var displayName = text(node, "displayName");
        if (displayName != null && !displayName.isBlank()) {
            return displayName.strip();
        }
        var name = field(node, "name");
        if (name == null || !name.isObject()) {
            return null;
        }
        var formatted = text(name, "formatted");
        if (formatted != null && !formatted.isBlank()) {
            return formatted.strip();
        }
        var joined = (nullToEmpty(text(name, "givenName")) + " " + nullToEmpty(text(name, "familyName"))).strip();
        return joined.isEmpty() ? null : joined;
    }

    static List<String> memberIds(JsonNode members) {
        if (members == null || members.isNull()) {
            return List.of();
        }
        if (!members.isArray()) {
            throw ScimException.invalidValue("members must be an array");
        }
        return members.valueStream().map(member -> member.isObject() ? text(member, "value") : member.asString(null))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    /** Entra ID schickt Wahrheitswerte gern als Zeichenkette ("False"). */
    static boolean bool(JsonNode node, boolean fallback) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return fallback;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isString()) {
            return switch (node.asString().trim().toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw ScimException.invalidValue("'" + node.asString() + "' is not a boolean");
            };
        }
        throw ScimException.invalidValue("active must be a boolean");
    }

    /** Attributnamen sind in SCIM case-insensitiv (RFC 7643 Abschnitt 2.1). */
    static JsonNode field(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return null;
        }
        var direct = node.get(name);
        if (direct != null) {
            return direct;
        }
        for (var property : node.properties()) {
            if (property.getKey().equalsIgnoreCase(name)) {
                return property.getValue();
            }
        }
        return null;
    }

    static String text(JsonNode node, String name) {
        var value = field(node, name);
        return value == null || value.isNull() || value.isMissingNode() ? null : value.asString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw ScimException.invalidSyntax("a JSON object is required");
        }
    }
}
