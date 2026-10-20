package de.tstieh.stoneintelligence.platform.scim;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.audit.AuditRecorder;
import de.tstieh.stoneintelligence.platform.identity.AuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.BlockedSubjects;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.Account;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.AccountData;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.Group;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.GroupData;
import de.tstieh.stoneintelligence.platform.scim.ScimResources.GroupInput;
import de.tstieh.stoneintelligence.platform.scim.ScimResources.UserInput;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * SCIM-Anwendungslogik (ADR 0013). Jede Aenderung, die Mitgliedschaften verschiebt, wird den
 * betroffenen Vaults angekuendigt (offene Verbindungen pruefen ihre Notizen neu) und dort protokolliert;
 * gesperrte Konten verlieren sofort jeden Zugriff ({@link BlockedSubjects}).
 */
@Service
public class ScimService {

    static final String ACTOR = "scim";

    private final DirectoryStore directory;
    private final AuthorizationRepository authorization;
    private final ScimSettings settings;
    private final VaultAnnouncementService announcements;
    private final BlockedSubjects blocked;
    private final AuditRecorder audit;

    public ScimService(DirectoryStore directory, AuthorizationRepository authorization, ScimSettings settings,
                       VaultAnnouncementService announcements, BlockedSubjects blocked, AuditRecorder audit) {
        this.directory = directory;
        this.authorization = authorization;
        this.settings = settings;
        this.announcements = announcements;
        this.blocked = blocked;
        this.audit = audit;
    }

    // --- Users ----------------------------------------------------------------------------------

    @Transactional
    public Account createUser(JsonNode body) {
        return saveUser(null, ScimResources.readUser(body));
    }

    public Account user(UUID id) {
        return directory.account(id).orElseThrow(() -> ScimException.notFound("User"));
    }

    public DirectoryStore.Page<Account> users(String filter, int startIndex, int count) {
        return directory.accounts(filter == null || filter.isBlank() ? null : ScimFilter.parse(filter), startIndex, count);
    }

    @Transactional
    public Account replaceUser(UUID id, JsonNode body) {
        user(id);
        return saveUser(id, ScimResources.readUser(body));
    }

    @Transactional
    public Account patchUser(UUID id, JsonNode body) {
        var current = user(id);
        var operations = ScimPatch.parse(body);
        var input = new UserInput(current.userName(), current.externalId(), current.displayName(), current.active());
        return saveUser(id, ScimPatch.applyToUser(input, operations));
    }

    @Transactional
    public void deleteUser(UUID id) {
        var account = user(id);
        var vaults = authorization.listAccessibleVaultIds(account.subject());
        directory.deleteAccount(id);
        blocked.changed();
        record(vaults, "SCIM_USER_DELETED", Map.of("subject", account.subject()));
    }

    private Account saveUser(UUID id, UserInput input) {
        var subject = settings.subjectOf(input.userName(), input.externalId());
        var previous = id == null ? null : directory.account(id).orElse(null);
        var affected = new LinkedHashSet<>(authorization.listAccessibleVaultIds(subject));
        if (previous != null && !previous.subject().equals(subject)) {
            affected.addAll(authorization.listAccessibleVaultIds(previous.subject()));
        }
        var saved = directory.saveAccount(id, new AccountData(subject, input.userName(), input.externalId(),
            input.displayName(), input.active()));
        affected.addAll(authorization.listAccessibleVaultIds(subject));
        var statusChanged = previous == null ? !saved.active() : previous.active() != saved.active();
        if (statusChanged || previous == null || !previous.subject().equals(subject)) {
            blocked.changed();
            affected.forEach(announcements::announceAccessChanged);
        }
        if (statusChanged) {
            record(affected, saved.active() ? "SCIM_USER_ACTIVATED" : "SCIM_USER_DEACTIVATED", Map.of("subject", subject));
        }
        return saved;
    }

    // --- Groups ---------------------------------------------------------------------------------

    @Transactional
    public Group createGroup(JsonNode body) {
        var input = ScimResources.readGroup(body);
        var group = directory.saveGroup(null, groupData(input));
        if (!input.memberIds().isEmpty()) {
            directory.addMembers(group.id(), ids(input.memberIds()));
            membersChanged(group.idpKey(), "SCIM_GROUP_MEMBERS_CHANGED", group);
        }
        return group;
    }

    public Group group(UUID id) {
        return directory.group(id).orElseThrow(() -> ScimException.notFound("Group"));
    }

    public List<DirectoryStore.Member> members(UUID groupId) {
        return directory.members(groupId);
    }

    public DirectoryStore.Page<Group> groups(String filter, int startIndex, int count) {
        return directory.groups(filter == null || filter.isBlank() ? null : ScimFilter.parse(filter), startIndex, count);
    }

    @Transactional
    public Group replaceGroup(UUID id, JsonNode body) {
        var previous = group(id);
        var input = ScimResources.readGroup(body);
        var saved = directory.saveGroup(id, groupData(input));
        directory.replaceMembers(id, ids(input.memberIds()));
        groupChanged(previous, saved);
        return saved;
    }

    @Transactional
    public Group patchGroup(UUID id, JsonNode body) {
        var previous = group(id);
        var patch = ScimPatch.applyToGroup(new GroupInput(previous.displayName(), previous.externalId(), List.of()),
            ScimPatch.parse(body), () -> directory.members(id));
        var saved = directory.saveGroup(id, groupData(patch.attributes()));
        for (var change : patch.members()) {
            var accounts = ids(List.copyOf(change.accountIds()));
            switch (change.action()) {
                case ADD -> directory.addMembers(id, accounts);
                case REMOVE -> directory.removeMembers(id, accounts);
                case REPLACE -> directory.replaceMembers(id, accounts);
            }
        }
        if (!patch.members().isEmpty() || !previous.idpKey().equals(saved.idpKey())) {
            groupChanged(previous, saved);
        }
        return saved;
    }

    @Transactional
    public void deleteGroup(UUID id) {
        var group = group(id);
        var vaults = authorization.vaultsLinking(Set.of(group.idpKey()));
        directory.deleteGroup(id);
        vaults.forEach(announcements::announceAccessChanged);
        record(vaults, "SCIM_GROUP_DELETED", Map.of("group", group.displayName()));
    }

    private GroupData groupData(GroupInput input) {
        return new GroupData(settings.groupKeyOf(input.displayName(), input.externalId()), input.displayName(), input.externalId());
    }

    private void groupChanged(Group previous, Group saved) {
        var keys = new HashSet<>(List.of(previous.idpKey(), saved.idpKey()));
        var vaults = authorization.vaultsLinking(keys);
        vaults.forEach(announcements::announceAccessChanged);
        record(vaults, "SCIM_GROUP_MEMBERS_CHANGED", Map.of("group", saved.displayName()));
    }

    private void membersChanged(String idpKey, String action, Group group) {
        var vaults = authorization.vaultsLinking(Set.of(idpKey));
        vaults.forEach(announcements::announceAccessChanged);
        record(vaults, action, Map.of("group", group.displayName()));
    }

    /** Ungueltige IDs koennen kein Konto sein - wie unbekannte uebergangen. */
    private static Set<UUID> ids(List<String> values) {
        var ids = new LinkedHashSet<UUID>();
        for (var value : values) {
            try {
                ids.add(UUID.fromString(value));
            } catch (IllegalArgumentException notAnId) {
                // uebergangen
            }
        }
        return ids;
    }

    private void record(Set<VaultId> vaults, String action, Map<String, Object> payload) {
        vaults.forEach(vault -> audit.record(vault, null, ACTOR, action, payload));
    }
}
