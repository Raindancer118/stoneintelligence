package de.tstieh.stoneintelligence.platform.invitation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Authentik-Ersatz: feste Nutzerliste, merkt sich angelegte und geloeschte Einladungen. */
public final class FakeUserDirectory implements UserDirectory {

    final List<DirectoryUser> users = new ArrayList<>();
    final List<String> createdInvitationsFor = new ArrayList<>();
    final List<String> deletedInvitations = new ArrayList<>();
    boolean available = true;

    public FakeUserDirectory with(String username, String name, String email) {
        users.add(new DirectoryUser(username, name, email));
        return this;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public List<DirectoryUser> search(String query, int limit) {
        var needle = query.toLowerCase(Locale.ROOT);
        return users.stream()
            .filter(user -> (user.username() + " " + user.name() + " " + user.email()).toLowerCase(Locale.ROOT).contains(needle))
            .limit(limit)
            .toList();
    }

    @Override
    public Optional<DirectoryUser> findByEmail(String email) {
        return users.stream().filter(user -> user.email().equalsIgnoreCase(email)).findFirst();
    }

    @Override
    public Optional<DirectoryUser> findByUsername(String username) {
        return users.stream().filter(user -> user.username().equals(username)).findFirst();
    }

    @Override
    public ExternalInvitation createInvitation(String email, Instant expiresAt) {
        createdInvitationsFor.add(email);
        var id = "ak-" + createdInvitationsFor.size();
        return new ExternalInvitation(id, "https://portal.example/if/flow/invite/?itoken=" + id);
    }

    @Override
    public void deleteInvitation(String id) {
        deletedInvitations.add(id);
    }
}
