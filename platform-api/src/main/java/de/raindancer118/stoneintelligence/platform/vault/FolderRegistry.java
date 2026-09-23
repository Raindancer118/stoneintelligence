package de.raindancer118.stoneintelligence.platform.vault;

import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.springframework.stereotype.Component;

/**
 * Ordner anlegen, verschieben, loeschen - und jedes Mal die anderen Geraete benachrichtigen.
 * Berechtigungen prueft der Aufrufer (Controller bzw. KI-Dienst).
 */
@Component
public class FolderRegistry {

    private final FolderRepository folders;
    private final VaultAnnouncementService announcements;

    public FolderRegistry(FolderRepository folders, VaultAnnouncementService announcements) {
        this.folders = folders;
        this.announcements = announcements;
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

    public void rename(VaultId vaultId, String from, String to, String actor) {
        folders.renameTree(vaultId, from, to, actor);
        announcements.announceFoldersChanged(vaultId, from);
        announcements.announceFoldersChanged(vaultId, to);
    }

    public void delete(VaultId vaultId, String path) {
        if (folders.deleteTree(vaultId, path) > 0) {
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
