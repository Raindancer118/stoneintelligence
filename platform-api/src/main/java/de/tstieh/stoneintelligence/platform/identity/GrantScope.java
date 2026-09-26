package de.tstieh.stoneintelligence.platform.identity;

import java.util.UUID;

/** Wen eine {@link AccessGrant} betrifft (ADR 0011): eine Person, eine Gruppe des Vaults oder alle Mitglieder. */
public sealed interface GrantScope {

    record Everyone() implements GrantScope {
    }

    record User(String subject) implements GrantScope {
    }

    record Group(UUID groupId) implements GrantScope {
    }

    static GrantScope everyone() {
        return new Everyone();
    }

    static GrantScope user(String subject) {
        return new User(subject);
    }

    static GrantScope group(UUID groupId) {
        return new Group(groupId);
    }

    /** Spalte {@code scope_type} in {@code platform.access_grants}. */
    default String type() {
        return switch (this) {
            case Everyone ignored -> "EVERYONE";
            case User ignored -> "USER";
            case Group ignored -> "GROUP";
        };
    }

    /** Spalte {@code scope_subject}: Subject bzw. Gruppen-Id, bei "alle" {@code null}. */
    default String subject() {
        return switch (this) {
            case Everyone ignored -> null;
            case User user -> user.subject();
            case Group group -> group.groupId().toString();
        };
    }

    static GrantScope of(String type, String subject) {
        return switch (type) {
            case "EVERYONE" -> everyone();
            case "USER" -> user(subject);
            case "GROUP" -> group(UUID.fromString(subject));
            default -> throw new IllegalArgumentException("unknown grant scope: " + type);
        };
    }
}
