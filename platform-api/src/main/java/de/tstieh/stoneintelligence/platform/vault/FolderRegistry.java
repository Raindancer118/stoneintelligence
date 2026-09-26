package de.tstieh.stoneintelligence.platform.vault;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.identity.AccessGrantRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ordner anlegen, verschieben, loeschen - und jedes Mal die anderen Geraete benachrichtigen.
 * Berechtigungen prueft der Aufrufer (Controller bzw. KI-Dienst).
 */
@Component
public class FolderRegistry {

    private final FolderRepository folders;
    private final VaultAnnouncementService announcements;
    private final AccessGrantRepository grants;

    public FolderRegistry(FolderRepository folders, VaultAnnouncementService announcements, AccessGrantRepository grants) {
        this.folders = folders;
        this.announcements = announcements;
        this.grants = grants;
    }

    public List<String> list(VaultId vaultId) {
        return folders.list(vaultId);
    }

    public void create(VaultId vaultId, String path, String actor) {
        announce(vaultId, folders.ensure(vaultId, path, actor));
    }

    /** Eine Notiz liegt jetzt unter {@code notePath}: ihre Ordner existieren damit auch. */
    public void ensureParentsOf(VaultId vaultId, String notePath, String actor) {
        var parents = FolderPaths.parentsOf(notePath);
        if (!parents.isEmpty()) {
            create(vaultId, parents.getLast(), actor);
        }
    }

    /** Die Freigaben des Ordners (ADR 0011) wandern mit - sonst galten sie ploetzlich fuer niemanden mehr. */
    @Transactional
    public void rename(VaultId vaultId, String from, String to, String actor) {
        folders.renameTree(vaultId, from, to, actor);
        grants.moveFolder(vaultId, from, to);
        announcements.announceFoldersChanged(vaultId, from);
        announcements.announceFoldersChanged(vaultId, to);
    }

    /**
     * Entfernt einen Ordner, den {@code creator} angelegt hat, wenn nichts mehr darin liegt - keine
     * Eintraege ({@code hasEntries}) und keine Unterordner. Liefert, ob er entfernt wurde.
     */
    public boolean deleteIfEmptyAndCreatedBy(VaultId vaultId, String path, String creator, boolean hasEntries) {
        if (hasEntries || !folders.creator(vaultId, path).map(creator::equals).orElse(false)) {
            return false;
        }
        if (folders.list(vaultId).stream().anyMatch(other -> other.startsWith(path + "/"))) {
            return false;
        }
        delete(vaultId, path);
        return true;
    }

    @Transactional
    public void delete(VaultId vaultId, String path) {
        if (folders.deleteTree(vaultId, path) > 0) {
            grants.removeFolder(vaultId, path);
            announcements.announceFoldersChanged(vaultId, path);
        }
    }

    private void announce(VaultId vaultId, List<String> created) {
        // Eine Ankuendigung je neuem Zweig genuegt - das Geraet holt ohnehin die ganze Liste.
        if (!created.isEmpty()) {
            announcements.announceFoldersChanged(vaultId, created.getLast());
        }
    }
}
