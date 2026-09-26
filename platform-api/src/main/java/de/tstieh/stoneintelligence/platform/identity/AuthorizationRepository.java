package de.tstieh.stoneintelligence.platform.identity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/**
 * Port fuer Rollen/Gruppen (Plan.md Abschnitt 4.4a): frei definierbare Rollen und Gruppen,
 * kein festes Vierer-Set (VIEWER/EDITOR/OWNER/ADMIN) wie im Vorgaenger stonesync.
 */
public interface AuthorizationRepository {

    Role createRole(VaultId vaultId, String name, Set<Permission> permissions);

    List<Role> listRoles(VaultId vaultId);

    Group createGroup(VaultId vaultId, String name);

    List<Group> listGroups(VaultId vaultId);

    boolean groupBelongsToVault(UUID groupId, VaultId vaultId);

    /** Rollen-IDs, die dieser Gruppe zugewiesen sind (s. {@link #assignRole}). */
    Set<UUID> listRoleIdsForGroup(UUID groupId);

    /** Zugewiesene Rollen aller Gruppen eines Vaults, ohne Einzelabfrage pro Gruppe. */
    Map<UUID, Set<UUID>> listRoleIdsByGroup(VaultId vaultId);

    void addMember(UUID groupId, String subject);

    void removeMember(UUID groupId, String subject);

    void assignRole(UUID groupId, UUID roleId);

    void unassignRole(UUID groupId, UUID roleId);

    /** Alle Vaults, in denen {@code subject} ueber irgendeine Gruppenmitgliedschaft Mitglied ist. */
    Set<VaultId> listAccessibleVaultIds(String subject);

    /**
     * Vereinigung aller Permissions aus allen Rollen aller Gruppen, denen {@code subject} in
     * diesem Vault angehoert (Allow-Aggregation auf dieser Ebene - Deny-Semantik lebt in den
     * Freigaben je Ordner/Eintrag, s. {@link AccessResolver}).
     */
    Set<Permission> effectivePermissions(VaultId vaultId, String subject);

    /** Gruppen und Vault-Rechte von {@code subject} in diesem Vault (Grundlage fuer {@link AccessResolver}). */
    Membership membership(VaultId vaultId, String subject);

    boolean roleBelongsToVault(UUID roleId, VaultId vaultId);

    void renameRole(UUID roleId, String name);

    void setRolePermissions(UUID roleId, Set<Permission> permissions);

    /** Entfernt die Rolle samt ihren Zuweisungen an Gruppen. */
    void deleteRole(UUID roleId);

    void renameGroup(UUID groupId, String name);

    /** Entfernt die Gruppe samt Mitgliedschaften und Rollen-Zuweisungen. */
    void deleteGroup(UUID groupId);

    /** Themen-/Tag-ACL (Plan.md Abschnitt 4.4a) - Praezedenz wird durch {@link TopicRules#resolve} berechnet. */
    TopicRule createTopicRule(VaultId vaultId, String topic, RuleScope scope, RuleEffect effect);

    List<TopicRule> listTopicRules(VaultId vaultId);
}
