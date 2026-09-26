package de.raindancer118.stoneintelligence.platform.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.vault.Note;
import de.raindancer118.stoneintelligence.platform.vault.NoteRepository;

/** In-Memory-Fake, spiegelt {@code JdbcAccessGrantRepository} (Eintrags-Pfade live aus dem Notiz-Speicher). */
public final class FakeAccessGrantRepository implements AccessGrantRepository {

    private final NoteRepository notes;
    private final List<AccessGrant> grants = new ArrayList<>();

    public FakeAccessGrantRepository(NoteRepository notes) {
        this.notes = notes;
    }

    @Override
    public synchronized List<AccessGrant> list(VaultId vaultId) {
        var result = new ArrayList<AccessGrant>();
        for (var grant : List.copyOf(grants)) {
            if (!grant.vaultId().equals(vaultId)) {
                continue;
            }
            if (grant.target() instanceof GrantTarget.Entry entry) {
                var current = notes.findById(vaultId, entry.noteId()).map(Note::path);
                if (current.isEmpty()) {
                    grants.remove(grant);
                    continue;
                }
                result.add(new AccessGrant(grant.id(), vaultId, GrantTarget.entry(entry.noteId(), current.get()),
                    grant.scope(), grant.permissions()));
            } else {
                result.add(grant);
            }
        }
        return result;
    }

    @Override
    public synchronized AccessGrant put(VaultId vaultId, GrantTarget target, GrantScope scope, Set<Permission> permissions, String actor) {
        grants.removeIf(grant -> grant.vaultId().equals(vaultId) && grant.target().sameTarget(target) && grant.scope().equals(scope));
        var grant = new AccessGrant(UUID.randomUUID(), vaultId, target, scope, permissions);
        grants.add(grant);
        return grant;
    }

    @Override
    public synchronized boolean remove(VaultId vaultId, GrantTarget target, GrantScope scope) {
        return grants.removeIf(grant -> grant.vaultId().equals(vaultId) && grant.target().sameTarget(target) && grant.scope().equals(scope));
    }

    @Override
    public synchronized void moveFolder(VaultId vaultId, String from, String to) {
        var source = AccessResolver.normalize(from);
        var target = AccessResolver.normalize(to);
        grants.replaceAll(grant -> {
            if (grant.vaultId().equals(vaultId) && grant.target() instanceof GrantTarget.Folder folder && isAtOrBelow(folder.path(), source)) {
                return new AccessGrant(grant.id(), vaultId, GrantTarget.folder(target + folder.path().substring(source.length())),
                    grant.scope(), grant.permissions());
            }
            return grant;
        });
    }

    @Override
    public synchronized void removeFolder(VaultId vaultId, String folder) {
        var path = AccessResolver.normalize(folder);
        grants.removeIf(grant -> grant.vaultId().equals(vaultId)
            && grant.target() instanceof GrantTarget.Folder f && isAtOrBelow(f.path(), path));
    }

    @Override
    public synchronized void removeSubject(VaultId vaultId, String subject) {
        grants.removeIf(grant -> grant.vaultId().equals(vaultId) && grant.scope().equals(GrantScope.user(subject)));
    }

    @Override
    public synchronized void removeGroup(VaultId vaultId, UUID groupId) {
        grants.removeIf(grant -> grant.vaultId().equals(vaultId) && grant.scope().equals(GrantScope.group(groupId)));
    }

    private static boolean isAtOrBelow(String path, String folder) {
        return path.equals(folder) || path.startsWith(folder + "/");
    }
}
