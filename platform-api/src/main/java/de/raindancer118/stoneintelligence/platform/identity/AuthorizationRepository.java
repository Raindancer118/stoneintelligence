package de.raindancer118.stoneintelligence.platform.identity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/**
 * Port fuer Rollen/Gruppen (Plan.md Abschnitt 4.4a): frei definierbare Rollen und Gruppen,
 * kein festes Vierer-Set (VIEWER/EDITOR/OWNER/ADMIN) wie im Vorgaenger stonesync.
 */
public interface AuthorizationRepository {

    Role createRole(VaultId vaultId, String name, Set<Permission> permissions);

    Group createGroup(VaultId vaultId, String name);

    void addMember(UUID groupId, String subject);

    void removeMember(UUID groupId, String subject);

    void assignRole(UUID groupId, UUID roleId);

    void unassignRole(UUID groupId, UUID roleId);

    /**
     * Vereinigung aller Permissions aus allen Rollen aller Gruppen, denen {@code subject} in
     * diesem Vault angehoert (Allow-Aggregation auf dieser Ebene - Deny-Semantik lebt in den
     * spezifischeren Ordner-/Themen-ACLs, s. {@link PathRules}).
     */
    Set<Permission> effectivePermissions(VaultId vaultId, String subject);

    /** Ordner-ACL (Plan.md Abschnitt 3) - Praezedenz wird durch {@link PathRules#resolve} berechnet. */
    PathRule createPathRule(VaultId vaultId, String pathPrefix, RuleScope scope, RuleEffect effect);

    List<PathRule> listPathRules(VaultId vaultId);

    /** Themen-/Tag-ACL (Plan.md Abschnitt 4.4a) - Praezedenz wird durch {@link TopicRules#resolve} berechnet. */
    TopicRule createTopicRule(VaultId vaultId, String topic, RuleScope scope, RuleEffect effect);

    List<TopicRule> listTopicRules(VaultId vaultId);
}
