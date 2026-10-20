package de.tstieh.stoneintelligence.platform.scim;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** {@code /Schemas} (RFC 7643 Abschnitt 7): nur die Attribute, die hier wirklich gespeichert werden. */
final class ScimSchemas {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private ScimSchemas() {
    }

    static ObjectNode user() {
        var schema = schema(ScimResources.USER_SCHEMA, "User", "User Account");
        var attributes = schema.putArray("attributes");
        attribute(attributes, "userName", "string", true, false, "server");
        attribute(attributes, "externalId", "string", false, true, "none");
        attribute(attributes, "displayName", "string", false, false, "none");
        var name = attribute(attributes, "name", "complex", false, false, "none");
        var sub = name.putArray("subAttributes");
        attribute(sub, "formatted", "string", false, false, "none");
        attribute(sub, "givenName", "string", false, false, "none");
        attribute(sub, "familyName", "string", false, false, "none");
        attribute(attributes, "active", "boolean", false, false, "none");
        return schema;
    }

    static ObjectNode group() {
        var schema = schema(ScimResources.GROUP_SCHEMA, "Group", "Group");
        var attributes = schema.putArray("attributes");
        attribute(attributes, "displayName", "string", true, false, "none");
        attribute(attributes, "externalId", "string", false, true, "none");
        var members = attribute(attributes, "members", "complex", false, false, "none").put("multiValued", true);
        var sub = members.putArray("subAttributes");
        attribute(sub, "value", "string", false, true, "none").put("mutability", "immutable");
        attribute(sub, "display", "string", false, false, "none").put("mutability", "readOnly");
        return schema;
    }

    private static ObjectNode schema(String id, String name, String description) {
        var schema = JSON.objectNode();
        schema.putArray("schemas").add("urn:ietf:params:scim:schemas:core:2.0:Schema");
        schema.put("id", id).put("name", name).put("description", description);
        schema.set("meta", JSON.objectNode().put("resourceType", "Schema").put("location", "/scim/v2/Schemas/" + id));
        return schema;
    }

    private static ObjectNode attribute(ArrayNode attributes, String name, String type, boolean required,
                                        boolean caseExact, String uniqueness) {
        return attributes.addObject()
            .put("name", name).put("type", type).put("multiValued", false).put("required", required)
            .put("caseExact", caseExact).put("mutability", "readWrite").put("returned", "default")
            .put("uniqueness", uniqueness);
    }
}
