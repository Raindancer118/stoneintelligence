package de.raindancer118.stoneintelligence.platform.vault;

import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.identity.Permission;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ordner eines Vaults. Das Plugin gleicht seine lokalen Ordner damit ab: leere Ordner kommen
 * ueberall an, ein geloeschter verschwindet ueberall. Pfadregeln gelten fuer einen Ordner wie fuer
 * alles darin (geprueft wird {@code <ordner>/}).
 */
@RestController
@RequestMapping("/api/v1/vaults/{vaultId}/folders")
public class FolderController {

    private final FolderRegistry folders;
    private final VaultAccessGuard access;

    public FolderController(FolderRegistry folders, VaultAccessGuard access) {
        this.folders = folders;
        this.access = access;
    }

    @GetMapping
    public List<String> list(@PathVariable String vaultId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        access.require(vId, auth.getName(), Permission.READ);
        return access.readablePaths(vId, auth.getName(), folders.list(vId), path -> path + "/");
    }

    @PostMapping
    @Transactional
    public FolderResponse create(@PathVariable String vaultId, @RequestBody FolderRequest request, Authentication auth) {
        var vId = VaultId.of(vaultId);
        var path = validated(request.path());
        access.require(vId, auth.getName(), Permission.CREATE, path + "/");
        folders.create(vId, path, auth.getName());
        return new FolderResponse(path);
    }

    @PostMapping("/rename")
    @Transactional
    public FolderResponse rename(@PathVariable String vaultId, @RequestBody RenameFolderRequest request, Authentication auth) {
        var vId = VaultId.of(vaultId);
        var from = validated(request.from());
        var to = validated(request.to());
        if (FolderPaths.isInside(to, from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A folder cannot be moved into itself");
        }
        access.require(vId, auth.getName(), Permission.WRITE, from + "/");
        access.require(vId, auth.getName(), Permission.CREATE, to + "/");
        folders.rename(vId, from, to, auth.getName());
        return new FolderResponse(to);
    }

    @DeleteMapping
    @Transactional
    public FolderResponse delete(@PathVariable String vaultId, @RequestParam String path, Authentication auth) {
        var vId = VaultId.of(vaultId);
        var folder = validated(path);
        access.require(vId, auth.getName(), Permission.DELETE, folder + "/");
        folders.delete(vId, folder);
        return new FolderResponse(folder);
    }

    private static String validated(String path) {
        if (!FolderPaths.isValid(path)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid folder path");
        }
        return path;
    }

    public record FolderRequest(String path) { }
    public record RenameFolderRequest(String from, String to) { }
    public record FolderResponse(String path) { }
}
