package de.raindancer118.stoneintelligence.platform.identity;

import java.util.Set;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vertrag, den {@code FakeAuthorizationRepository} und {@code JdbcAuthorizationRepository} beide erfuellen. */
public abstract class AuthorizationRepositoryContractTest {

    protected abstract AuthorizationRepository repository();

    @Nested
    class EffectivePermissions {

        @Test
        void should_beEmpty_when_subjectBelongsToNoGroup() {
            var repository = repository();
            var vaultId = VaultId.newId();

            assertThat(repository.effectivePermissions(vaultId, "tom")).isEmpty();
        }

        @Test
        void should_containRolePermissions_when_subjectIsMemberOfGroupWithThatRole() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var role = repository.createRole(vaultId, "editor", Set.of(Permission.READ, Permission.WRITE));
            var group = repository.createGroup(vaultId, "editors");
            repository.assignRole(group.id(), role.id());
            repository.addMember(group.id(), "tom");

            assertThat(repository.effectivePermissions(vaultId, "tom"))
                .containsExactlyInAnyOrder(Permission.READ, Permission.WRITE);
        }

        @Test
        void should_unionPermissions_when_subjectIsMemberOfMultipleGroupsWithDifferentRoles() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var readerRole = repository.createRole(vaultId, "reader", Set.of(Permission.READ));
            var deleterRole = repository.createRole(vaultId, "deleter", Set.of(Permission.DELETE));
            var readers = repository.createGroup(vaultId, "readers");
            var deleters = repository.createGroup(vaultId, "deleters");
            repository.assignRole(readers.id(), readerRole.id());
            repository.assignRole(deleters.id(), deleterRole.id());
            repository.addMember(readers.id(), "tom");
            repository.addMember(deleters.id(), "tom");

            assertThat(repository.effectivePermissions(vaultId, "tom"))
                .containsExactlyInAnyOrder(Permission.READ, Permission.DELETE);
        }

        @Test
        void should_notGrantPermissions_when_memberWasRemovedFromGroup() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var role = repository.createRole(vaultId, "editor", Set.of(Permission.WRITE));
            var group = repository.createGroup(vaultId, "editors");
            repository.assignRole(group.id(), role.id());
            repository.addMember(group.id(), "tom");
            repository.removeMember(group.id(), "tom");

            assertThat(repository.effectivePermissions(vaultId, "tom")).isEmpty();
        }

        @Test
        void should_rejectAssignRole_when_groupAndRoleBelongToDifferentVaults() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var otherVaultId = VaultId.newId();
            var role = repository.createRole(otherVaultId, "editor", Set.of(Permission.WRITE));
            var group = repository.createGroup(vaultId, "editors");

            assertThatThrownBy(() -> repository.assignRole(group.id(), role.id()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_notGrantPermissions_when_roleWasUnassignedFromGroup() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var role = repository.createRole(vaultId, "editor", Set.of(Permission.WRITE));
            var group = repository.createGroup(vaultId, "editors");
            repository.assignRole(group.id(), role.id());
            repository.addMember(group.id(), "tom");
            repository.unassignRole(group.id(), role.id());

            assertThat(repository.effectivePermissions(vaultId, "tom")).isEmpty();
        }

        @Test
        void should_notLeakPermissions_when_subjectHasSameNameInDifferentVault() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var otherVaultId = VaultId.newId();
            var role = repository.createRole(otherVaultId, "editor", Set.of(Permission.WRITE));
            var group = repository.createGroup(otherVaultId, "editors");
            repository.assignRole(group.id(), role.id());
            repository.addMember(group.id(), "tom");

            assertThat(repository.effectivePermissions(vaultId, "tom")).isEmpty();
        }
    }

    @Nested
    class PathRuleStorage {

        @Test
        void should_listCreatedRule_when_queriedForItsVault() {
            var repository = repository();
            var vaultId = VaultId.newId();

            repository.createPathRule(vaultId, "private", RuleScope.everyone(), RuleEffect.DENY);

            assertThat(repository.listPathRules(vaultId))
                .containsExactly(new PathRule("private", RuleScope.everyone(), RuleEffect.DENY));
        }

        @Test
        void should_notLeakRulesFromOtherVaults_when_listing() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var otherVaultId = VaultId.newId();
            repository.createPathRule(otherVaultId, "private", RuleScope.everyone(), RuleEffect.DENY);

            assertThat(repository.listPathRules(vaultId)).isEmpty();
        }

        @Test
        void should_preserveUserScopedRule_when_storedAndListed() {
            var repository = repository();
            var vaultId = VaultId.newId();

            repository.createPathRule(vaultId, "private", RuleScope.user("tom"), RuleEffect.ALLOW);

            assertThat(repository.listPathRules(vaultId))
                .containsExactly(new PathRule("private", RuleScope.user("tom"), RuleEffect.ALLOW));
        }
    }

    @Nested
    class TopicRuleStorage {

        @Test
        void should_listCreatedRule_when_queriedForItsVault() {
            var repository = repository();
            var vaultId = VaultId.newId();

            repository.createTopicRule(vaultId, "finance", RuleScope.everyone(), RuleEffect.DENY);

            assertThat(repository.listTopicRules(vaultId))
                .containsExactly(new TopicRule("finance", RuleScope.everyone(), RuleEffect.DENY));
        }

        @Test
        void should_notLeakRulesFromOtherVaults_when_listing() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var otherVaultId = VaultId.newId();
            repository.createTopicRule(otherVaultId, "finance", RuleScope.everyone(), RuleEffect.DENY);

            assertThat(repository.listTopicRules(vaultId)).isEmpty();
        }
    }
}
