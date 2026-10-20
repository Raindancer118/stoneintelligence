package de.tstieh.stoneintelligence.platform.scim;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.Member;
import de.tstieh.stoneintelligence.platform.scim.ScimResources.GroupInput;
import de.tstieh.stoneintelligence.platform.scim.ScimResources.UserInput;
import tools.jackson.databind.JsonNode;

/**
 * PATCH nach RFC 7644 Abschnitt 3.5.2, so tolerant wie die IdPs es brauchen: Operationen in jeder
 * Schreibweise ("Replace"), Wahrheitswerte als Zeichenkette, Attribute ohne Pfad als Objekt. Was hier
 * nicht gespeichert wird (E-Mails, Titel, Erweiterungen), wird uebergangen statt abgelehnt - sonst
 * scheiterte der ganze Abgleich an einem Attribut, das niemand braucht.
 */
final class ScimPatch {

    enum Op {
        ADD, REMOVE, REPLACE
    }

    record Operation(Op op, String path, JsonNode value) {
    }

    enum MemberAction {
        ADD, REMOVE, REPLACE
    }

    record MemberChange(MemberAction action, Set<String> accountIds) {
    }

    /** Ergebnis fuer Gruppen: neue Attribute plus Mitglieder-Aenderungen in der Reihenfolge des Requests. */
    record GroupPatch(GroupInput attributes, List<MemberChange> members) {
    }

    private ScimPatch() {
    }

    static List<Operation> parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ScimException.invalidSyntax("a PatchOp object is required");
        }
        var operations = ScimResources.field(body, "Operations");
        if (operations == null || !operations.isArray() || operations.isEmpty()) {
            throw ScimException.invalidSyntax("Operations must be a non-empty array");
        }
        var result = new ArrayList<Operation>();
        for (var operation : operations) {
            var name = ScimResources.text(operation, "op");
            Op op;
            try {
                op = Op.valueOf(name == null ? "" : name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw ScimException.invalidSyntax("unknown op '" + name + "'");
            }
            var path = ScimResources.text(operation, "path");
            var value = ScimResources.field(operation, "value");
            if (path == null && op != Op.REMOVE && (value == null || !value.isObject())) {
                throw ScimException.invalidValue("without a path the value must be an object");
            }
            if (path == null && op == Op.REMOVE) {
                throw new ScimException(org.springframework.http.HttpStatus.BAD_REQUEST, "noTarget", "remove needs a path");
            }
            result.add(new Operation(op, path == null ? null : path.trim(), value));
        }
        return result;
    }

    static UserInput applyToUser(UserInput current, List<Operation> operations) {
        var user = new MutableUser(current);
        for (var operation : operations) {
            if (operation.path() == null) {
                for (var property : operation.value().properties()) {
                    user.apply(operation.op(), ScimFilter.normalize(property.getKey()), property.getValue());
                }
            } else {
                user.apply(operation.op(), ScimFilter.normalize(operation.path()), operation.value());
            }
        }
        return user.result();
    }

    /** {@code currentMembers}: nur geladen, wenn ein Pfad-Filter wie {@code members[display eq "Ben"]} es braucht. */
    static GroupPatch applyToGroup(GroupInput current, List<Operation> operations, Supplier<List<Member>> currentMembers) {
        var displayName = current.displayName();
        var externalId = current.externalId();
        var members = new ArrayList<MemberChange>();
        for (var operation : operations) {
            var entries = operation.path() == null
                ? operation.value().properties().stream().map(e -> Map.entry(ScimFilter.normalize(e.getKey()), e.getValue())).toList()
                : List.of(Map.entry(ScimFilter.normalize(operation.path()), nullToMissing(operation.value())));
            for (var entry : entries) {
                var path = entry.getKey();
                var value = entry.getValue();
                var lower = path.toLowerCase(Locale.ROOT);
                if (lower.equals("displayname")) {
                    if (operation.op() == Op.REMOVE) {
                        throw ScimException.invalidValue("displayName is required");
                    }
                    displayName = requireText(value, "displayName");
                } else if (lower.equals("externalid")) {
                    externalId = operation.op() == Op.REMOVE ? null : value.asString(null);
                } else if (lower.equals("members")) {
                    members.add(memberChange(operation.op(), value));
                } else if (lower.startsWith("members[")) {
                    if (operation.op() != Op.REMOVE) {
                        throw ScimException.invalidPath("only remove supports a filtered members path");
                    }
                    members.add(new MemberChange(MemberAction.REMOVE, matchingMembers(path, currentMembers.get())));
                }
            }
        }
        return new GroupPatch(new GroupInput(displayName, externalId, current.memberIds()), members);
    }

    private static MemberChange memberChange(Op op, JsonNode value) {
        var ids = new LinkedHashSet<>(ScimResources.memberIds(value == null || value.isMissingNode() ? null : value));
        return switch (op) {
            case ADD -> new MemberChange(MemberAction.ADD, ids);
            case REPLACE -> new MemberChange(MemberAction.REPLACE, ids);
            // Ohne Wert: alle Mitglieder entfernen (RFC 7644 Abschnitt 3.5.2.2); Entra ID schickt die zu entfernenden als Wert.
            case REMOVE -> ids.isEmpty() && (value == null || value.isMissingNode() || value.isNull())
                ? new MemberChange(MemberAction.REPLACE, Set.of())
                : new MemberChange(MemberAction.REMOVE, ids);
        };
    }

    private static Set<String> matchingMembers(String path, List<Member> current) {
        var open = path.indexOf('[');
        var close = path.lastIndexOf(']');
        if (close < open || close != path.length() - 1) {
            throw ScimException.invalidPath("cannot read path '" + path + "'");
        }
        ScimFilter filter;
        try {
            filter = ScimFilter.parse(path.substring(open + 1, close));
        } catch (ScimException invalid) {
            throw ScimException.invalidPath(invalid.getMessage());
        }
        var ids = new LinkedHashSet<String>();
        for (var member : current) {
            Map<String, Object> attributes = member.displayName() == null
                ? Map.of("value", member.accountId().toString())
                : Map.of("value", member.accountId().toString(), "display", member.displayName());
            if (filter.matches(attributes::get)) {
                ids.add(member.accountId().toString());
            }
        }
        return ids;
    }

    private static JsonNode nullToMissing(JsonNode value) {
        return value == null ? tools.jackson.databind.node.MissingNode.getInstance() : value;
    }

    private static String requireText(JsonNode value, String attribute) {
        var text = value == null || value.isNull() || value.isMissingNode() ? null : value.asString(null);
        if (text == null || text.isBlank()) {
            throw ScimException.invalidValue(attribute + " must not be empty");
        }
        return text.strip();
    }

    private static final class MutableUser {

        private String userName;
        private String externalId;
        private String displayName;
        private String givenName;
        private String familyName;
        private boolean nameParts;
        private boolean active;

        MutableUser(UserInput current) {
            userName = current.userName();
            externalId = current.externalId();
            displayName = current.displayName();
            active = current.active();
        }

        void apply(Op op, String path, JsonNode value) {
            var remove = op == Op.REMOVE;
            switch (path.toLowerCase(Locale.ROOT)) {
                case "username" -> {
                    if (remove) {
                        throw ScimException.invalidValue("userName is required");
                    }
                    userName = requireText(value, "userName");
                }
                case "externalid" -> externalId = remove ? null : value.asString(null);
                case "displayname", "name.formatted" -> displayName = remove ? null : value.asString(null);
                case "name" -> {
                    if (remove) {
                        displayName = null;
                    } else if (value != null && value.isObject()) {
                        var formatted = ScimResources.text(value, "formatted");
                        if (formatted != null) {
                            displayName = formatted;
                        } else {
                            givenName = ScimResources.text(value, "givenName");
                            familyName = ScimResources.text(value, "familyName");
                            nameParts = true;
                        }
                    }
                }
                case "name.givenname" -> {
                    givenName = remove ? null : value.asString(null);
                    nameParts = true;
                }
                case "name.familyname" -> {
                    familyName = remove ? null : value.asString(null);
                    nameParts = true;
                }
                case "active" -> active = !remove && ScimResources.bool(value, true);
                default -> {
                    // Nicht gespeichert (emails, title, Enterprise-Erweiterung, ...) - bewusst uebergangen.
                }
            }
        }

        UserInput result() {
            var name = displayName;
            if ((name == null || name.isBlank()) && nameParts) {
                var joined = ((givenName == null ? "" : givenName) + " " + (familyName == null ? "" : familyName)).strip();
                name = joined.isEmpty() ? null : joined;
            }
            return new UserInput(userName, externalId, name == null || name.isBlank() ? null : name.strip(), active);
        }
    }
}
