package de.tstieh.stoneintelligence.platform.vault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public final class FakeFolderRepository implements FolderRepository {

    private final Map<VaultId, TreeSet<String>> folders = new ConcurrentHashMap<>();
    private final Map<String, String> creators = new ConcurrentHashMap<>();

    private TreeSet<String> of(VaultId vaultId) {
        return folders.computeIfAbsent(vaultId, id -> new TreeSet<>());
    }

    @Override
    public synchronized List<String> list(VaultId vaultId) {
        return List.copyOf(of(vaultId));
    }

    @Override
    public synchronized List<String> ensure(VaultId vaultId, String path, String actor) {
        var created = new ArrayList<String>();
        for (var folder : FolderPaths.withParents(path)) {
            if (of(vaultId).add(folder)) {
                created.add(folder);
                creators.put(vaultId.value() + "|" + folder, actor);
            }
        }
        return created;
    }

    @Override
    public synchronized java.util.Optional<String> creator(VaultId vaultId, String path) {
        return of(vaultId).contains(path) ? java.util.Optional.ofNullable(creators.get(vaultId.value() + "|" + path))
            : java.util.Optional.empty();
    }

    @Override
    public synchronized int deleteTree(VaultId vaultId, String path) {
        var inside = of(vaultId).stream().filter(folder -> FolderPaths.isInside(folder, path)).toList();
        of(vaultId).removeAll(inside);
        return inside.size();
    }

    @Override
    public synchronized void renameTree(VaultId vaultId, String from, String to, String actor) {
        var inside = of(vaultId).stream().filter(folder -> FolderPaths.isInside(folder, from)).toList();
        if (inside.isEmpty()) {
            return;
        }
        of(vaultId).removeAll(inside);
        ensure(vaultId, to, actor);
        inside.forEach(folder -> of(vaultId).add(to + folder.substring(from.length())));
    }
}
