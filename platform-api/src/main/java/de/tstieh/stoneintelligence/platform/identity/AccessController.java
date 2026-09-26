package de.tstieh.stoneintelligence.platform.identity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.audit.AuditRecorder;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.vault.FolderNotFoundException;
import de.tstieh.stoneintelligence.platform.vault.FolderRepository;
import de.tstieh.stoneintelligence.platform.vault.ForbiddenException;
import de.tstieh.stoneintelligence.platform.vault.NoteNotFoundException;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Freigaben je Ordner und Eintrag ansehen und aendern (ADR 0011) - fuer das Kontextmenue
 * "Freigabe…" in Obsidian und den Freigabe-Dialog der Webapp.
 *
 * <p>Ansehen darf, wer den Eintrag lesen kann; er sieht dann aber nur seine eigenen Rechte. Wer
 * an der Stelle {@link Permission#MANAGE} hat, sieht alle Freigaben und was jedes Mitglied dort
 * darf, und kann Freigaben setzen - hoechstens mit den Rechten, die er dort selbst hat. Freigaben
 * gehen nur an Mitglieder und Gruppen dieses Vaults.
 */
@RestController
public class AccessController {

    private final VaultAccessGuard access;
    private final AccessGrantRepository grants;
    private final AuthorizationRepository authorization;
    private final NoteRepository notes;
    private final FolderRepository folders;
    private final AuditRecorder audit;
    private final VaultAnnouncementService announcements;

    public AccessController(VaultAccessGuard access, AccessGrantRepository grants, AuthorizationRepository authorization,
                            NoteRepository notes, FolderRepository folders, AuditRecorder audit,
                            VaultAnnouncementService announcements) {
        this.access = access;
        this.grants = grants;
        this.authorization = authorization;
        this.notes = notes;
        this.folders = folders;
        this.audit = audit;
        this.announcements = announcements;
    }

    @GetMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/access")
    public AccessReport noteAccess(@PathVariable String vaultId, @PathVariable String noteId, Authentication auth) {
        var vId = VaultId.of(vaultId);
        return report(vId, entryOf(vId, noteId), auth.getName());
    }

    /** {@code path} ist der Ordnerpfad; leer = der ganze Vault. */
    @GetMapping("/api/v1/vaults/{vaultId}/folders/access")
    public AccessReport folderAccess(@PathVariable String vaultId, @RequestParam(defaultValue = "") String path,
                                     Authentication auth) {
        var vId = VaultId.of(vaultId);
        return report(vId, folderOf(vId, path), auth.getName());
    }

    @PutMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/access/grants")
    @Transactional
    public GrantResponse putNoteGrant(@PathVariable String vaultId, @PathVariable String noteId,
                                      @RequestBody GrantRequest request, Authentication auth) {
        var vId = VaultId.of(vaultId);
        return put(vId, entryOf(vId, noteId), request, auth.getName());
    }

    @PutMapping("/api/v1/vaults/{vaultId}/folders/access/grants")
    @Transactional
    public GrantResponse putFolderGrant(@PathVariable String vaultId, @RequestParam(defaultValue = "") String path,
                                        @RequestBody GrantRequest request, Authentication auth) {
        var vId = VaultId.of(vaultId);
        return put(vId, folderOf(vId, path), request, auth.getName());
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/notes/{noteId}/access/grants")
    @Transactional
    public void removeNoteGrant(@PathVariable String vaultId, @PathVariable String noteId,
                                @RequestParam String scopeType, @RequestParam(required = false) String subject,
                                Authentication auth) {
        var vId = VaultId.of(vaultId);
        remove(vId, entryOf(vId, noteId), scopeOf(scopeType, subject), auth.getName());
    }

    @DeleteMapping("/api/v1/vaults/{vaultId}/folders/access/grants")
    @Transactional
    public void removeFolderGrant(@PathVariable String vaultId, @RequestParam(defaultValue = "") String path,
                                  @RequestParam String scopeType, @RequestParam(required = false) String subject,
                                  Authentication auth) {
        var vId = VaultId.of(vaultId);
        remove(vId, folderOf(vId, path), scopeOf(scopeType, subject), auth.getName());
    }

    private AccessReport report(VaultId vaultId, GrantTarget target, String actor) {
        var path = rulePathOf(target);
        var all = grants.list(vaultId);
        var mine = AccessResolver.resolve(authorization.membership(vaultId, actor), all, path);
        if (!mine.allows(Permission.READ) && !mine.allows(Permission.MANAGE)) {
            throw new ForbiddenException(actor + " may not see '" + path + "'");
        }
        var groupNames = groupNames(vaultId);
        var mineResponse = new MineResponse(mine.permissions(), GrantResponse.from(mine.source(), groupNames));
        if (!mine.allows(Permission.MANAGE)) {
            return new AccessReport(TargetResponse.from(target), mineResponse, List.of(), List.of(), List.of());
        }
        var onTarget = all.stream().filter(grant -> grant.target().sameTarget(target))
            .map(grant -> GrantResponse.from(grant, groupNames)).toList();
        var inherited = all.stream()
            .filter(grant -> grant.target() instanceof GrantTarget.Folder && !grant.target().sameTarget(target))
            .filter(grant -> covers((GrantTarget.Folder) grant.target(), path))
            .sorted(Comparator.comparing(grant -> ((GrantTarget.Folder) grant.target()).path()))
            .map(grant -> GrantResponse.from(grant, groupNames)).toList();
        var members = new ArrayList<MemberAccess>();
        for (var subject : memberSubjects(vaultId)) {
            var membership = authorization.membership(vaultId, subject);
            var effective = AccessResolver.resolve(membership, all, path);
            members.add(new MemberAccess(subject,
                membership.groupIds().stream().map(groupNames::get).sorted().toList(),
                effective.permissions(), GrantResponse.from(effective.source(), groupNames)));
        }
        return new AccessReport(TargetResponse.from(target), mineResponse, onTarget, inherited, members);
    }

    private GrantResponse put(VaultId vaultId, GrantTarget target, GrantRequest request, String actor) {
        var mine = requireManage(vaultId, target, actor);
        var scope = scopeOf(request.scopeType(), request.subject());
        requireInVault(vaultId, scope);
        Set<Permission> permissions = request.permissions() == null ? null
            : request.permissions().isEmpty() ? Set.of() : EnumSet.copyOf(request.permissions());
        if (permissions != null && !mine.permissions().containsAll(permissions)) {
            throw new ForbiddenException(actor + " may not hand out more than " + mine.permissions());
        }
        var grant = grants.put(vaultId, target, scope, permissions, actor);
        audit.record(vaultId, noteIdOf(target), actor, "ACCESS_GRANTED", payload(target, scope, permissions));
        announcements.announceAccessChanged(vaultId);
        return GrantResponse.from(grant, groupNames(vaultId));
    }

    private void remove(VaultId vaultId, GrantTarget target, GrantScope scope, String actor) {
        requireManage(vaultId, target, actor);
        if (grants.remove(vaultId, target, scope)) {
            audit.record(vaultId, noteIdOf(target), actor, "ACCESS_REVOKED", payload(target, scope, null));
            announcements.announceAccessChanged(vaultId);
        }
    }

    private EffectiveAccess requireManage(VaultId vaultId, GrantTarget target, String actor) {
        var mine = access.accessAt(vaultId, actor, rulePathOf(target));
        if (!mine.allows(Permission.MANAGE)) {
            throw new ForbiddenException(actor + " may not manage access to '" + rulePathOf(target) + "'");
        }
        return mine;
    }

    private void requireInVault(VaultId vaultId, GrantScope scope) {
        switch (scope) {
            case GrantScope.User user -> {
                if (!authorization.membership(vaultId, user.subject()).isMember()) {
                    throw new InvalidGrantException(user.subject() + " is no member of this vault");
                }
            }
            case GrantScope.Group group -> {
                if (!authorization.groupBelongsToVault(group.groupId(), vaultId)) {
                    throw new InvalidGrantException("group " + group.groupId() + " does not belong to this vault");
                }
            }
            case GrantScope.Everyone ignored -> {
            }
        }
    }

    private GrantTarget entryOf(VaultId vaultId, String noteId) {
        var id = NoteId.of(noteId);
        var note = notes.findById(vaultId, id).orElseThrow(() -> new NoteNotFoundException(vaultId, id));
        return GrantTarget.entry(note.id(), note.path());
    }

    private GrantTarget folderOf(VaultId vaultId, String path) {
        var folder = new GrantTarget.Folder(path);
        if (!folder.path().isEmpty() && !folders.list(vaultId).contains(folder.path())) {
            throw new FolderNotFoundException(vaultId, folder.path());
        }
        return folder;
    }

    /** Der Pfad, an dem {@link AccessResolver} fuer dieses Ziel gefragt wird (Ordner mit {@code /}). */
    private static String rulePathOf(GrantTarget target) {
        return switch (target) {
            case GrantTarget.Entry entry -> entry.path();
            case GrantTarget.Folder folder -> folder.path() + "/";
        };
    }

    private static boolean covers(GrantTarget.Folder folder, String path) {
        return folder.path().isEmpty() || path.startsWith(folder.path() + "/");
    }

    private static NoteId noteIdOf(GrantTarget target) {
        return target instanceof GrantTarget.Entry entry ? entry.noteId() : null;
    }

    private static GrantScope scopeOf(String type, String subject) {
        if (type == null) {
            throw new InvalidGrantException("scopeType is required");
        }
        try {
            var scope = GrantScope.of(type, "EVERYONE".equals(type) ? null : subject);
            if (!(scope instanceof GrantScope.Everyone) && (subject == null || subject.isBlank())) {
                throw new InvalidGrantException(type + " needs a subject");
            }
            return scope;
        } catch (IllegalArgumentException invalid) {
            throw new InvalidGrantException(invalid.getMessage());
        }
    }

    private static Map<String, Object> payload(GrantTarget target, GrantScope scope, Set<Permission> permissions) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("target", TargetResponse.from(target).kind());
        payload.put("path", TargetResponse.from(target).path());
        payload.put("scopeType", scope.type());
        payload.put("subject", scope.subject());
        payload.put("permissions", permissions == null ? null : new TreeSet<>(permissions).stream().map(Enum::name).toList());
        return payload;
    }

    private Map<UUID, String> groupNames(VaultId vaultId) {
        var names = new LinkedHashMap<UUID, String>();
        authorization.listGroups(vaultId).forEach(group -> names.put(group.id(), group.name()));
        return names;
    }

    private List<String> memberSubjects(VaultId vaultId) {
        return authorization.listGroups(vaultId).stream()
            .flatMap(group -> group.memberSubjects().stream()).distinct().sorted().toList();
    }

    /** {@code permissions == null} heisst "wie im Vault", eine leere Liste "nichts". */
    public record GrantRequest(String scopeType, String subject, List<Permission> permissions) {
    }

    public record TargetResponse(String kind, String path, String noteId) {
        static TargetResponse from(GrantTarget target) {
            return switch (target) {
                case GrantTarget.Folder folder -> new TargetResponse("folder", folder.path(), null);
                case GrantTarget.Entry entry -> new TargetResponse("entry", entry.path(), entry.noteId().value().toString());
            };
        }
    }

    public record GrantResponse(String id, TargetResponse target, String scopeType, String subject, String groupName,
                                Set<Permission> permissions, boolean inheritsVault) {
        static GrantResponse from(AccessGrant grant, Map<UUID, String> groupNames) {
            if (grant == null) {
                return null;
            }
            var groupName = grant.scope() instanceof GrantScope.Group group ? groupNames.get(group.groupId()) : null;
            return new GrantResponse(grant.id().toString(), TargetResponse.from(grant.target()), grant.scope().type(),
                grant.scope().subject(), groupName, grant.permissions(), grant.inheritsVault());
        }
    }

    /** {@code source == null}: die Rechte kommen aus den Vault-Rollen. */
    public record MineResponse(Set<Permission> permissions, GrantResponse source) {
    }

    public record MemberAccess(String subject, List<String> groups, Set<Permission> permissions, GrantResponse source) {
    }

    public record AccessReport(TargetResponse target, MineResponse mine, List<GrantResponse> grants,
                               List<GrantResponse> inherited, List<MemberAccess> members) {
    }
}
