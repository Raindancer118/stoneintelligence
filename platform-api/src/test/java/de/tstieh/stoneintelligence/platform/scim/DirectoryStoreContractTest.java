package de.tstieh.stoneintelligence.platform.scim;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.AuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.AccountData;
import de.tstieh.stoneintelligence.platform.scim.DirectoryStore.GroupData;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vertrag fuer {@code FakeDirectoryStore} und {@code JdbcDirectoryStore} - inklusive der Wirkung auf Vaults. */
public abstract class DirectoryStoreContractTest {

    protected abstract DirectoryStore store();

    /** Dasselbe Repository, dessen Mitgliedschaften der Store beeinflusst. */
    protected abstract AuthorizationRepository authorization();

    protected abstract VaultId newVault();

    private static AccountData person(String userName) {
        return new AccountData(userName, userName, "ext-" + userName, "Person " + userName, true);
    }

    @Nested
    class Accounts {

        @Test
        void should_createReadAndReplaceAccounts() {
            var store = store();
            var created = store.saveAccount(null, person("ben"));

            assertThat(store.account(created.id())).contains(created);
            assertThat(created.created()).isNotNull();

            var replaced = store.saveAccount(created.id(), new AccountData("ben", "ben", "ext-ben", "Ben Becker", true));
            assertThat(replaced.id()).isEqualTo(created.id());
            assertThat(replaced.displayName()).isEqualTo("Ben Becker");
            assertThat(replaced.created()).isEqualTo(created.created());
            assertThat(store.account(UUID.randomUUID())).isEmpty();
        }

        @Test
        void should_refuseADuplicateUserName_caseInsensitively() {
            var store = store();
            store.saveAccount(null, person("ben"));

            assertThatThrownBy(() -> store.saveAccount(null, new AccountData("BEN2", "BEN", null, null, true)))
                .isInstanceOfSatisfying(ScimException.class, error -> assertThat(error.scimType()).isEqualTo("uniqueness"));
        }

        @Test
        void should_filterAndPage_inCreationOrder() {
            var store = store();
            for (var name : new String[] {"anna", "ben", "berta", "carl"}) {
                store.saveAccount(null, person(name));
            }

            var page = store.accounts(ScimFilter.parse("userName sw \"B\""), 1, 1);
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.items()).extracting(DirectoryStore.Account::userName).containsExactly("ben");
            assertThat(store.accounts(ScimFilter.parse("userName sw \"b\""), 2, 10).items())
                .extracting(DirectoryStore.Account::userName).containsExactly("berta");
            assertThat(store.accounts(null, 1, 100).total()).isEqualTo(4);
            assertThat(store.accounts(ScimFilter.parse("externalId eq \"ext-carl\" and active eq true"), 1, 10).items())
                .extracting(DirectoryStore.Account::userName).containsExactly("carl");
            assertThat(store.accounts(ScimFilter.parse("displayName co \"ANN\" or userName eq \"carl\""), 1, 10).total()).isEqualTo(2);
            assertThat(store.accounts(ScimFilter.parse("not (userName eq \"anna\")"), 1, 10).total()).isEqualTo(3);
            assertThat(store.accounts(null, 1, 0).items()).isEmpty();
        }

        @Test
        void should_escapeLikeWildcards_inContainsFilters() {
            var store = store();
            store.saveAccount(null, new AccountData("a_b", "a_b", null, null, true));
            store.saveAccount(null, new AccountData("axb", "axb", null, null, true));

            assertThat(store.accounts(ScimFilter.parse("userName co \"_\""), 1, 10).items())
                .extracting(DirectoryStore.Account::userName).containsExactly("a_b");
        }

        // Geloeschte bleiben gesperrt, bis der IdP sie neu anlegt - dann mit derselben ID.
        @Test
        void should_hideDeletedAccounts_keepThemBlocked_andReviveThemOnCreate() {
            var store = store();
            var ben = store.saveAccount(null, person("ben"));

            assertThat(store.deleteAccount(ben.id())).isPresent();
            assertThat(store.deleteAccount(ben.id())).isEmpty();
            assertThat(store.account(ben.id())).isEmpty();
            assertThat(store.accounts(null, 1, 10).total()).isZero();
            assertThat(store.blockedSubjects()).containsExactly("ben");

            var revived = store.saveAccount(null, person("ben"));
            assertThat(revived.id()).isEqualTo(ben.id());
            assertThat(store.blockedSubjects()).isEmpty();
        }

        // Datenminimierung: vom geloeschten Konto bleibt nur, was die Sperre braucht - und auch das nur 30 Tage.
        @Test
        void should_forgetNamesOfDeletedAccounts_andPurgeThemAfterTheRetention() {
            var store = store();
            var ben = store.saveAccount(null, person("ben"));
            store.deleteAccount(ben.id());

            assertThat(store.purgeDeletedBefore(Instant.now().minus(DirectoryStore.DELETED_RETENTION))).isZero();
            assertThat(store.blockedSubjects()).containsExactly("ben");
            assertThat(store.purgeDeletedBefore(Instant.now().plusSeconds(60))).isEqualTo(1);
            assertThat(store.blockedSubjects()).isEmpty();
            assertThat(store.saveAccount(null, person("ben")).id()).isNotEqualTo(ben.id());
        }
    }

    @Nested
    class Groups {

        @Test
        void should_keepMembers_ignoringUnknownAccounts() {
            var store = store();
            var ben = store.saveAccount(null, person("ben"));
            var anna = store.saveAccount(null, person("anna"));
            var sales = store.saveGroup(null, new GroupData("Sales", "Sales", "g-1"));

            store.replaceMembers(sales.id(), Set.of(ben.id(), UUID.randomUUID()));
            store.addMembers(sales.id(), Set.of(anna.id(), ben.id()));
            assertThat(store.members(sales.id())).extracting(DirectoryStore.Member::accountId).containsExactlyInAnyOrder(ben.id(), anna.id());
            assertThat(store.members(sales.id())).extracting(DirectoryStore.Member::displayName).contains("Person ben");

            store.removeMembers(sales.id(), Set.of(ben.id()));
            assertThat(store.members(sales.id())).extracting(DirectoryStore.Member::accountId).containsExactly(anna.id());
            assertThat(store.idpKeysOf(anna.id())).containsExactly("Sales");
        }

        @Test
        void should_findGroupsByNameOrMember_andDeleteThem() {
            var store = store();
            var ben = store.saveAccount(null, person("ben"));
            var sales = store.saveGroup(null, new GroupData("Sales", "Sales", null));
            store.saveGroup(null, new GroupData("Support", "Support", null));
            store.addMembers(sales.id(), Set.of(ben.id()));

            assertThat(store.groups(ScimFilter.parse("displayName eq \"sales\""), 1, 10).items())
                .extracting(DirectoryStore.Group::id).containsExactly(sales.id());
            assertThat(store.groups(ScimFilter.parse("members[value eq \"" + ben.id() + "\"]"), 1, 10).items())
                .extracting(DirectoryStore.Group::id).containsExactly(sales.id());
            assertThat(store.groups(null, 1, 10).total()).isEqualTo(2);

            assertThat(store.deleteGroup(sales.id())).isTrue();
            assertThat(store.deleteGroup(sales.id())).isFalse();
            assertThat(store.group(sales.id())).isEmpty();
            assertThat(store.idpKeysOf(ben.id())).isEmpty();
        }

        @Test
        void should_refuseADuplicateGroupKey() {
            var store = store();
            store.saveGroup(null, new GroupData("Sales", "Sales", null));

            assertThatThrownBy(() -> store.saveGroup(null, new GroupData("Sales", "Sales", null)))
                .isInstanceOf(ScimException.class);
        }
    }

    // Was SCIM meldet, entscheidet ueber die Mitgliedschaft in verknuepften Vault-Gruppen.
    @Nested
    class EffectOnVaults {

        @Test
        void should_makeScimGroupMembersVaultMembers() {
            var store = store();
            var vault = newVault();
            var role = authorization().createRole(vault, "lesen", Set.of(Permission.READ));
            var group = authorization().createGroup(vault, "Vertrieb");
            authorization().assignRole(group.id(), role.id());
            authorization().linkIdpGroup(group.id(), "Sales");
            var ben = store.saveAccount(null, person("ben"));
            var sales = store.saveGroup(null, new GroupData("Sales", "Sales", null));

            store.addMembers(sales.id(), Set.of(ben.id()));
            assertThat(authorization().effectivePermissions(vault, "ben")).containsExactly(Permission.READ);
            assertThat(authorization().countIdpMembers(vault)).containsEntry(group.id(), 1);

            store.removeMembers(sales.id(), Set.of(ben.id()));
            assertThat(authorization().membership(vault, "ben").isMember()).isFalse();
        }

        // Ein altes Token haelt niemanden in einer Gruppe, aus der SCIM ihn genommen hat.
        @Test
        void should_ignoreTokenGroups_ofAccountsManagedByScim() {
            var store = store();
            var vault = newVault();
            authorization().linkIdpGroup(authorization().createGroup(vault, "Vertrieb").id(), "Sales");
            authorization().rememberTokenGroups("ben", Set.of("Sales"), Instant.now());
            assertThat(authorization().membership(vault, "ben").isMember()).isTrue();

            store.saveAccount(null, person("ben"));

            assertThat(authorization().membership(vault, "ben").isMember()).isFalse();
            assertThat(authorization().rememberTokenGroups("ben", Set.of(), Instant.now())).isEmpty();
        }

        @Test
        void should_removeDeactivatedAccounts_fromEveryVault_includingDirectMemberships() {
            var store = store();
            var vault = newVault();
            authorization().addMember(authorization().createGroup(vault, "Projekt").id(), "ben");
            var ben = store.saveAccount(null, person("ben"));

            store.saveAccount(ben.id(), new AccountData("ben", "ben", null, null, false));
            assertThat(authorization().membership(vault, "ben").isMember()).isFalse();
            assertThat(authorization().listAccessibleVaultIds("ben")).doesNotContain(vault);
            assertThat(store.blockedSubjects()).containsExactly("ben");

            store.saveAccount(ben.id(), new AccountData("ben", "ben", null, null, true));
            assertThat(authorization().membership(vault, "ben").isMember()).isTrue();
        }
    }
}
