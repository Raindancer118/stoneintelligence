package de.tstieh.stoneintelligence.platform.scim;

import java.util.Locale;

/**
 * Wie SCIM-Ressourcen auf das abgebildet werden, was im Token steht (ADR 0013).
 *
 * @param subjectAttribute welches SCIM-Attribut dem Principal-Claim entspricht: {@code userName}
 *                         (Authentik, Okta mit preferred_username) oder {@code externalId} (Entra ID mit oid)
 * @param groupKey         welches Attribut einer SCIM-Gruppe im Gruppen-Claim steht: {@code displayName}
 *                         (Authentik, Okta) oder {@code externalId} (Entra ID: Objekt-IDs)
 */
public record ScimSettings(Attribute subjectAttribute, Attribute groupKey) {

    public enum Attribute {
        USER_NAME, DISPLAY_NAME, EXTERNAL_ID;

        public static Attribute parse(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "username" -> USER_NAME;
                case "displayname" -> DISPLAY_NAME;
                case "externalid" -> EXTERNAL_ID;
                default -> throw new IllegalArgumentException("Unknown SCIM attribute: " + value);
            };
        }
    }

    public ScimSettings {
        if (subjectAttribute == Attribute.DISPLAY_NAME) {
            throw new IllegalArgumentException("The subject cannot be the display name");
        }
        if (groupKey == Attribute.USER_NAME) {
            throw new IllegalArgumentException("Groups have no userName");
        }
    }

    public static ScimSettings defaults() {
        return new ScimSettings(Attribute.USER_NAME, Attribute.DISPLAY_NAME);
    }

    String subjectOf(String userName, String externalId) {
        var subject = subjectAttribute == Attribute.EXTERNAL_ID ? externalId : userName;
        if (subject == null || subject.isBlank()) {
            throw ScimException.invalidValue((subjectAttribute == Attribute.EXTERNAL_ID ? "externalId" : "userName")
                + " is required: it identifies the person in their sign-in token");
        }
        return subject;
    }

    String groupKeyOf(String displayName, String externalId) {
        var key = groupKey == Attribute.EXTERNAL_ID ? externalId : displayName;
        if (key == null || key.isBlank()) {
            throw ScimException.invalidValue((groupKey == Attribute.EXTERNAL_ID ? "externalId" : "displayName")
                + " is required: it is the group name in sign-in tokens");
        }
        return key;
    }
}
