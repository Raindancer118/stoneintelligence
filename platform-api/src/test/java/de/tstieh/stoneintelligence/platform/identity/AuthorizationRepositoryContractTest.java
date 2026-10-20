package de.tstieh.stoneintelligence.platform.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vertrag, den {@code FakeAuthorizationRepository} und {@code JdbcAuthorizationRepository} beide erfuellen. */
public abstract class AuthorizationRepositoryContractTest {

    protected abstract AuthorizationRepository repository();

    /**
     * Erzeugt einen Vault, der bei einer echten DB tatsaechlich existiert - `roles`/`groups`/
     * `path_rules`/`topic_rules` haben Fremdschluessel-Constraints auf `platform.vaults`.
     */
    protected abstract VaultId newVault();

    @Test
    void should_scopeGroupLookupToVault_andRejectUnknownGroups() {
        var repository = repository();
        var vaultId = newVault();
        var otherVault = newVault();
        var group = repository.createGroup(vaultId, "team");

        assertThat(repository.groupBelongsToVault(group.id(), vaultId)).isTrue();
        assertThat(repository.groupBelongsToVault(group.id(), otherVault)).isFalse();
        assertThat(repository.groupBelongsToVault(java.util.UUID.randomUUID(), vaultId)).isFalse();
    }

    @Nested
    class EffectivePermissions {

        @Test
        void should_beEmpty_when_subjectBelongsToNoGroup() {
            var repository = repository();
            var vaultId = newVault();

            assertThat(repository.effectivePermissions(vaultId, "tom")).isEmpty();
        }

        @Test
        void should_containRolePermissions_when_subjectIsMemberOfGroupWithThatRole() {
            var repository = repository();
            var vaultId = newVault();
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
            var vaultId = newVault();
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
            var vaultId = newVault();
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
            var vaultId = newVault();
            var otherVaultId = newVault();
            var role = repository.createRole(otherVaultId, "editor", Set.of(Permission.WRITE));
            var group = repository.createGroup(vaultId, "editors");

            assertThatThrownBy(() -> repository.assignRole(group.id(), role.id()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_notGrantPermissions_when_roleWasUnassignedFromGroup() {
            var repository = repository();
            var vaultId = newVault();
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
            var vaultId = newVault();
            var otherVaultId = newVault();
            var role = repository.createRole(otherVaultId, "editor", Set.of(Permission.WRITE));
            var group = repository.createGroup(otherVaultId, "editors");
            repository.assignRole(group.id(), role.id());
            repository.addMember(group.id(), "tom");

            assertThat(repository.effectivePermissions(vaultId, "tom")).isEmpty();
        }
    }

    @Nested
    class RoleAndGroupListing {

        @Test
        void should_listCreatedRoles_when_queriedForVault() {
            var repository = repository();
            var vaultId = newVault();
            var role = repository.createRole(vaultId, "editor", Set.of(Permission.READ, Permission.WRITE));

            assertThat(repository.listRoles(vaultId)).containsExactly(role);
        }

        @Test
        void should_notLeakRoles_fromOtherVaults() {
            var repository = repository();
            var vaultId = newVault();
            var otherVaultId = newVault();
            repository.createRole(otherVaultId, "editor", Set.of(Permission.READ));

            assertThat(repository.listRoles(vaultId)).isEmpty();
        }

        @Test
        void should_listCreatedGroupsWithMembers_when_queriedForVault() {
            var repository = repository();
            var vaultId = newVault();
            var group = repository.createGroup(vaultId, "editors");
            repository.addMember(group.id(), "tom");

            assertThat(repository.listGroups(vaultId))
                .extracting(Group::id, Group::name, Group::memberSubjects)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(group.id(), "editors", Set.of("tom")));
        }

        @Test
        void should_listAssignedRoleIds_forGroup() {
            var repository = repository();
            var vaultId = newVault();
            var role = repository.createRole(vaultId, "editor", Set.of(Permission.WRITE));
            var group = repository.createGroup(vaultId, "editors");
            repository.assignRole(group.id(), role.id());

            assertThat(repository.listRoleIdsForGroup(group.id())).containsExactly(role.id());
        }

        @Test
        void should_listAccessibleVaultIds_forSubjectAcrossVaults() {
            var repository = repository();
            var vaultId = newVault();
            var otherVaultId = newVault();
            var group = repository.createGroup(vaultId, "editors");
            repository.addMember(group.id(), "tom");

            assertThat(repository.listAccessibleVaultIds("tom")).containsExactly(vaultId);
            assertThat(repository.listAccessibleVaultIds("tom")).doesNotContain(otherVaultId);
        }
    }

    @Nested
    class MembershipLookup {

        @Test
        void should_nameGroupsAndVaultPermissions_evenForAGroupWithoutRoles() {
            var repository = repository();
            var vaultId = newVault();
            var role = repository.createRole(vaultId, "reader", Set.of(Permission.READ));
            var readers = repository.createGroup(vaultId, "readers");
            var guests = repository.createGroup(vaultId, "guests");
            repository.assignRole(readers.id(), role.id());
            repository.addMember(readers.id(), "tom");
            repository.addMember(guests.id(), "tom");
            repository.addMember(guests.id(), "guest");

            var tom = repository.membership(vaultId, "tom");
            var guest = repository.membership(vaultId, "guest");

            assertThat(tom.groupIds()).containsExactlyInAnyOrder(readers.id(), guests.id());
            assertThat(tom.vaultPermissions()).containsExactly(Permission.READ);
            assertThat(guest.isMember()).isTrue();
            assertThat(guest.vaultPermissions()).isEmpty();
            assertThat(repository.membership(newVault(), "tom").isMember()).isFalse();
        }
    }

    @Nested
    class RoleAndGroupManagement {

        @Test
        void should_renameAndRedefineARole() {
            var repository = repository();
            var vaultId = newVault();
            var role = repository.createRole(vaultId, "reader", Set.of(Permission.READ));

            repository.renameRole(role.id(), "editor");
            repository.setRolePermissions(role.id(), Set.of(Permission.READ, Permission.WRITE));

            assertThat(repository.listRoles(vaultId)).singleElement().satisfies(changed -> {
                assertThat(changed.name()).isEqualTo("editor");
                assertThat(changed.permissions()).containsExactlyInAnyOrder(Permission.READ, Permission.WRITE);
            });
            assertThat(repository.roleBelongsToVault(role.id(), vaultId)).isTrue();
            assertThat(repository.roleBelongsToVault(role.id(), newVault())).isFalse();
        }

        @Test
        void should_takeTheRightsAway_whenARoleIsDeleted() {
            var repository = repository();
            var vaultId = newVault();
            var role = repository.createRole(vaultId, "editor", Set.of(Permission.WRITE));
            var group = repository.createGroup(vaultId, "editors");
            repository.assignRole(group.id(), role.id());
            repository.addMember(group.id(), "tom");

            repository.deleteRole(role.id());

            assertThat(repository.listRoles(vaultId)).isEmpty();
            assertThat(repository.listRoleIdsForGroup(group.id())).isEmpty();
            assertThat(repository.effectivePermissions(vaultId, "tom")).isEmpty();
        }

        @Test
        void should_renameAGroup_andEndItsMemberships_whenDeleted() {
            var repository = repository();
            var vaultId = newVault();
            var role = repository.createRole(vaultId, "editor", Set.of(Permission.WRITE));
            var group = repository.createGroup(vaultId, "editors");
            repository.assignRole(group.id(), role.id());
            repository.addMember(group.id(), "tom");

            repository.renameGroup(group.id(), "writers");
            assertThat(repository.listGroups(vaultId)).extracting(Group::name).containsExactly("writers");

            repository.deleteGroup(group.id());

            assertThat(repository.listGroups(vaultId)).isEmpty();
            assertThat(repository.membership(vaultId, "tom").isMember()).isFalse();
            assertThat(repository.listRoles(vaultId)).hasSize(1);
        }
    }

    // ADR 0013 Schritt 5: wer in der verknuepften Gruppe des Firmen-IdP ist, ist Mitglied - ohne Einladung.
    @Nested
    class IdpGroups {

        private final Instant now = Instant.now();

        @Test
        void should_makeEveryoneInALinkedIdpGroupAMember() {
            var repository = repository();
            var vaultId = newVault();
            var role = repository.createRole(vaultId, "lesen", Set.of(Permission.READ));
            var everyone = repository.createGroup(vaultId, "Alle Beschaeftigten");
            repository.assignRole(everyone.id(), role.id());
            repository.linkIdpGroup(everyone.id(), "all-employees");

            repository.rememberTokenGroups("ben", Set.of("all-employees", "football-club"), now);

            assertThat(repository.membership(vaultId, "ben").groupIds()).containsExactly(everyone.id());
            assertThat(repository.effectivePermissions(vaultId, "ben")).containsExactly(Permission.READ);
            assertThat(repository.listAccessibleVaultIds("ben")).contains(vaultId);
            assertThat(repository.membership(vaultId, "eve").isMember()).isFalse();
            assertThat(repository.listGroups(vaultId)).singleElement().extracting(Group::idpGroup).isEqualTo("all-employees");
            assertThat(repository.linkedIdpGroups()).contains("all-employees").doesNotContain("football-club");
        }

        @Test
        void should_endMembership_whenTheIdpNoLongerListsThePerson_orTheLinkIsRemoved() {
            var repository = repository();
            var vaultId = newVault();
            var group = repository.createGroup(vaultId, "Vertrieb");
            repository.linkIdpGroup(group.id(), "sales");
            repository.rememberTokenGroups("ben", Set.of("sales"), now);
            repository.rememberTokenGroups("anna", Set.of("sales"), now);

            repository.rememberTokenGroups("ben", Set.of(), now);
            assertThat(repository.membership(vaultId, "ben").isMember()).isFalse();
            assertThat(repository.membership(vaultId, "anna").isMember()).isTrue();

            repository.linkIdpGroup(group.id(), null);
            assertThat(repository.membership(vaultId, "anna").isMember()).isFalse();
            assertThat(repository.listGroups(vaultId).getFirst().idpGroup()).isNull();
        }

        // Wer sich eine Woche nicht angemeldet hat, bleibt nicht ewig ueber ein altes Token Mitglied.
        @Test
        void should_forgetTokenGroups_afterAWeekWithoutSignIn() {
            var repository = repository();
            var vaultId = newVault();
            var group = repository.createGroup(vaultId, "Vertrieb");
            repository.linkIdpGroup(group.id(), "sales");

            repository.rememberTokenGroups("ben", Set.of("sales"), now.minus(Duration.ofDays(8)));

            assertThat(repository.membership(vaultId, "ben").isMember()).isFalse();
        }

        @Test
        void should_keepDirectMembers_nextToIdpMembers() {
            var repository = repository();
            var vaultId = newVault();
            var group = repository.createGroup(vaultId, "Vertrieb");
            repository.linkIdpGroup(group.id(), "sales");
            repository.addMember(group.id(), "extern");

            assertThat(repository.membership(vaultId, "extern").isMember()).isTrue();
            assertThat(repository.listGroups(vaultId).getFirst().memberSubjects()).containsExactly("extern");
        }

        // Nur echte Aenderungen sollen Caches leeren und Geraete wecken - nicht das stuendliche Auffrischen.
        @Test
        void should_reportTheVaultsWhoseMembershipChanged() {
            var repository = repository();
            var vaultId = newVault();
            var otherVault = newVault();
            repository.linkIdpGroup(repository.createGroup(vaultId, "Vertrieb").id(), "sales");
            repository.linkIdpGroup(repository.createGroup(otherVault, "Alle").id(), "all-employees");

            assertThat(repository.rememberTokenGroups("ben", Set.of("sales"), now)).containsExactly(vaultId);
            assertThat(repository.rememberTokenGroups("ben", Set.of("sales"), now.plusSeconds(60))).isEmpty();
            assertThat(repository.rememberTokenGroups("ben", Set.of("sales", "all-employees"), now)).containsExactly(otherVault);
            assertThat(repository.rememberTokenGroups("ben", Set.of(), now)).containsExactlyInAnyOrder(vaultId, otherVault);
        }

        // Kam jemand nach Ablauf wieder, ist das fuer die Caches ein Beitritt, kein Auffrischen.
        @Test
        void should_treatASignInAfterExpiryAsJoiningAgain() {
            var repository = repository();
            var vaultId = newVault();
            repository.linkIdpGroup(repository.createGroup(vaultId, "Vertrieb").id(), "sales");
            repository.rememberTokenGroups("ben", Set.of("sales"), now.minus(Duration.ofDays(9)));

            assertThat(repository.rememberTokenGroups("ben", Set.of("sales"), now)).containsExactly(vaultId);
            assertThat(repository.membership(vaultId, "ben").isMember()).isTrue();
        }

        @Test
        void should_sweepExpiredTokenGroups_andReportTheirVaults() {
            var repository = repository();
            var vaultId = newVault();
            repository.linkIdpGroup(repository.createGroup(vaultId, "Vertrieb").id(), "sales");
            repository.rememberTokenGroups("old", Set.of("sales"), now.minus(Duration.ofDays(8)));
            repository.rememberTokenGroups("fresh", Set.of("sales"), now);

            assertThat(repository.forgetTokenGroupsSeenBefore(now.minus(AuthorizationRepository.TOKEN_GROUPS_VALID_FOR)))
                .containsExactly(vaultId);
            assertThat(repository.forgetTokenGroupsSeenBefore(now.minus(AuthorizationRepository.TOKEN_GROUPS_VALID_FOR))).isEmpty();
            assertThat(repository.membership(vaultId, "fresh").isMember()).isTrue();
        }

        @Test
        void should_countIdpMembersPerLinkedGroup() {
            var repository = repository();
            var vaultId = newVault();
            var sales = repository.createGroup(vaultId, "Vertrieb");
            var plain = repository.createGroup(vaultId, "Projekt");
            repository.linkIdpGroup(sales.id(), "sales");
            repository.addMember(plain.id(), "tom");
            repository.rememberTokenGroups("ben", Set.of("sales"), now);
            repository.rememberTokenGroups("anna", Set.of("sales"), now);
            repository.rememberTokenGroups("gone", Set.of("sales"), now.minus(Duration.ofDays(30)));

            assertThat(repository.countIdpMembers(vaultId)).containsExactly(Map.entry(sales.id(), 2));
        }

        @Test
        void should_refuseLinkingTheSameIdpGroupTwiceInOneVault_butAllowItAcrossVaults() {
            var repository = repository();
            var vaultId = newVault();
            repository.linkIdpGroup(repository.createGroup(vaultId, "Vertrieb").id(), "sales");
            var second = repository.createGroup(vaultId, "Vertrieb 2");

            assertThatThrownBy(() -> repository.linkIdpGroup(second.id(), "sales"))
                .isInstanceOf(IdpGroupAlreadyLinkedException.class);
            repository.linkIdpGroup(repository.createGroup(newVault(), "Vertrieb").id(), "sales");
        }
    }

    @Nested
    class TopicRuleStorage {

        @Test
        void should_listCreatedRule_when_queriedForItsVault() {
            var repository = repository();
            var vaultId = newVault();

            repository.createTopicRule(vaultId, "finance", RuleScope.everyone(), RuleEffect.DENY);

            assertThat(repository.listTopicRules(vaultId))
                .containsExactly(new TopicRule("finance", RuleScope.everyone(), RuleEffect.DENY));
        }

        @Test
        void should_notLeakRulesFromOtherVaults_when_listing() {
            var repository = repository();
            var vaultId = newVault();
            var otherVaultId = newVault();
            repository.createTopicRule(otherVaultId, "finance", RuleScope.everyone(), RuleEffect.DENY);

            assertThat(repository.listTopicRules(vaultId)).isEmpty();
        }
    }
}
