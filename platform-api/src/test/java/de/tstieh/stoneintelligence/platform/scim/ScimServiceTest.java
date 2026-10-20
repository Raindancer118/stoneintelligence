package de.tstieh.stoneintelligence.platform.scim;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.identity.BlockedSubjects;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.identity.SubjectBlocked;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeDirectoryStore directory = new FakeDirectoryStore(authorization);
    private final VaultAnnouncementService announcements = new VaultAnnouncementService();
    private final List<VaultId> announced = new ArrayList<>();
    private final List<Object> events = new ArrayList<>();
    private final List<String> audit = new ArrayList<>();
    private final BlockedSubjects blocked = new BlockedSubjects(directory::blockedSubjects, events::add);
    private final VaultId vault = VaultId.newId();

    {
        announcements.addAccessListener(announced::add);
        var group = authorization.createGroup(vault, "Vertrieb");
        authorization.assignRole(group.id(), authorization.createRole(vault, "lesen", Set.of(Permission.READ)).id());
        authorization.linkIdpGroup(group.id(), "Sales");
        blocked.isBlocked("nobody");
    }

    private ScimService service(ScimSettings settings) {
        return new ScimService(directory, authorization, settings, announcements, blocked,
            (vaultId, note, actor, action, payload) -> audit.add(vaultId.equals(vault) ? action : "other:" + action));
    }

    private final ScimService scim = service(ScimSettings.defaults());

    private static JsonNode json(String json) {
        return MAPPER.readTree(json.replace('\'', '"'));
    }

    private static String patch(String operations) {
        return "{'schemas':['urn:ietf:params:scim:api:messages:2.0:PatchOp'],'Operations':" + operations + "}";
    }

    private UUID createUser(String userName) {
        return scim.createUser(json("{'schemas':['urn:ietf:params:scim:schemas:core:2.0:User'],'userName':'" + userName
            + "','name':{'givenName':'Ben','familyName':'Becker'},'active':true}")).id();
    }

    private UUID createSales(UUID... members) {
        var list = new StringBuilder();
        for (var member : members) {
            list.append(list.isEmpty() ? "" : ",").append("{'value':'").append(member).append("'}");
        }
        return scim.createGroup(json("{'displayName':'Sales','members':[" + list + "]}")).id();
    }

    @Nested
    class Users {

        @Test
        void should_createUsers_withTheNameFromItsParts() {
            var id = createUser("ben@example.com");

            assertThat(scim.user(id).displayName()).isEqualTo("Ben Becker");
            assertThat(scim.user(id).subject()).isEqualTo("ben@example.com");
        }

        // So deaktiviert Entra ID ein Konto - mit "False" als Zeichenkette.
        @Test
        void should_endAllAccessImmediately_whenTheIdpDeactivatesSomeone() {
            var ben = createUser("ben");
            createSales(ben);
            assertThat(authorization.membership(vault, "ben").isMember()).isTrue();
            announced.clear();

            scim.patchUser(ben, json(patch("[{'op':'Replace','path':'active','value':'False'}]")));

            assertThat(authorization.membership(vault, "ben").isMember()).isFalse();
            assertThat(blocked.isBlocked("ben")).isTrue();
            assertThat(events).containsExactly(new SubjectBlocked("ben"));
            assertThat(announced).containsExactly(vault);
            assertThat(audit).contains("SCIM_USER_DEACTIVATED");
        }

        @Test
        void should_reactivate_andApplyAttributesWithoutAPath() {
            var ben = createUser("ben");
            createSales(ben);
            scim.patchUser(ben, json(patch("[{'op':'replace','value':{'active':false}}]")));

            scim.patchUser(ben, json(patch("[{'op':'replace','value':{'active':true,'displayName':'Ben B.','emails':[{'value':'x'}]}}]")));

            assertThat(scim.user(ben).active()).isTrue();
            assertThat(scim.user(ben).displayName()).isEqualTo("Ben B.");
            assertThat(blocked.isBlocked("ben")).isFalse();
            assertThat(audit).contains("SCIM_USER_DEACTIVATED", "SCIM_USER_ACTIVATED");
        }

        @Test
        void should_hideAndBlockDeletedUsers() {
            var ben = createUser("ben");
            createSales(ben);

            scim.deleteUser(ben);

            assertThatThrownBy(() -> scim.user(ben)).isInstanceOfSatisfying(ScimException.class,
                error -> assertThat(error.status().value()).isEqualTo(404));
            assertThat(blocked.isBlocked("ben")).isTrue();
            assertThat(authorization.membership(vault, "ben").isMember()).isFalse();
            assertThat(audit).contains("SCIM_USER_DELETED");
        }

        // Entra ID mit oid als Principal-Claim: die Person ist im Token ihre Objekt-ID, nicht ihr UPN.
        @Test
        void should_useTheExternalIdAsSubject_whenConfigured() {
            var entra = service(new ScimSettings(ScimSettings.Attribute.EXTERNAL_ID, ScimSettings.Attribute.EXTERNAL_ID));

            var ben = entra.createUser(json("{'userName':'ben@contoso.com','externalId':'5f2c-oid','displayName':'Ben'}"));

            assertThat(ben.subject()).isEqualTo("5f2c-oid");
            assertThatThrownBy(() -> entra.createUser(json("{'userName':'anna@contoso.com'}")))
                .isInstanceOfSatisfying(ScimException.class, error -> assertThat(error.scimType()).isEqualTo("invalidValue"));
        }

        @Test
        void should_findUsersByFilter_likeIdpsDoBeforeCreating() {
            createUser("ben@example.com");
            createUser("anna@example.com");

            var page = scim.users("userName eq \"BEN@example.com\"", 1, 100);

            assertThat(page.total()).isEqualTo(1);
            assertThat(page.items().getFirst().userName()).isEqualTo("ben@example.com");
        }

        @Test
        void should_rejectBrokenRequests_withScimErrors() {
            var ben = createUser("ben");

            assertThatThrownBy(() -> scim.createUser(json("{'displayName':'no userName'}")))
                .isInstanceOfSatisfying(ScimException.class, error -> assertThat(error.scimType()).isEqualTo("invalidValue"));
            assertThatThrownBy(() -> scim.patchUser(ben, json(patch("[{'op':'move','path':'active'}]"))))
                .isInstanceOfSatisfying(ScimException.class, error -> assertThat(error.scimType()).isEqualTo("invalidSyntax"));
            assertThatThrownBy(() -> scim.patchUser(ben, json(patch("[{'op':'remove','path':'userName'}]"))))
                .isInstanceOf(ScimException.class);
            assertThatThrownBy(() -> scim.createUser(json("{'userName':'BEN'}")))
                .isInstanceOfSatisfying(ScimException.class, error -> assertThat(error.status().value()).isEqualTo(409));
        }
    }

    @Nested
    class Groups {

        @Test
        void should_addAndRemoveMembers_theWayEntraIdAndOktaDo() {
            var ben = createUser("ben");
            var anna = createUser("anna");
            var carl = createUser("carl");
            var sales = createSales(ben);

            scim.patchGroup(sales, json(patch("[{'op':'Add','path':'members','value':[{'value':'" + anna + "'},{'value':'" + carl + "'}]}]")));
            assertThat(authorization.membership(vault, "anna").isMember()).isTrue();

            // Entra ID: remove mit Wertliste
            scim.patchGroup(sales, json(patch("[{'op':'Remove','path':'members','value':[{'value':'" + anna + "'}]}]")));
            // Okta/RFC: remove mit Filterpfad
            scim.patchGroup(sales, json(patch("[{'op':'remove','path':'members[value eq \\\"" + carl + "\\\"]'}]")));

            assertThat(scim.members(sales)).extracting(DirectoryStore.Member::accountId).containsExactly(ben);
            assertThat(authorization.membership(vault, "anna").isMember()).isFalse();
            assertThat(authorization.membership(vault, "carl").isMember()).isFalse();
            assertThat(audit).contains("SCIM_GROUP_MEMBERS_CHANGED");
        }

        @Test
        void should_replaceAllMembers_withPut_andRemoveAllWithoutValue() {
            var ben = createUser("ben");
            var anna = createUser("anna");
            var sales = createSales(ben);

            scim.replaceGroup(sales, json("{'displayName':'Sales','members':[{'value':'" + anna + "'}]}"));
            assertThat(scim.members(sales)).extracting(DirectoryStore.Member::accountId).containsExactly(anna);

            scim.patchGroup(sales, json(patch("[{'op':'remove','path':'members'}]")));
            assertThat(scim.members(sales)).isEmpty();
        }

        // Benennt der IdP die Gruppe um, passt die Verknuepfung nicht mehr - beide Seiten muessen es merken.
        @Test
        void should_announceToVaultsOfTheOldAndNewName_whenAGroupIsRenamed() {
            var otherVault = VaultId.newId();
            authorization.linkIdpGroup(authorization.createGroup(otherVault, "EMEA").id(), "Sales EMEA");
            var ben = createUser("ben");
            var sales = createSales(ben);
            announced.clear();

            scim.patchGroup(sales, json(patch("[{'op':'Replace','path':'displayName','value':'Sales EMEA'}]")));

            assertThat(announced).containsExactlyInAnyOrder(vault, otherVault);
            assertThat(authorization.membership(vault, "ben").isMember()).isFalse();
            assertThat(authorization.membership(otherVault, "ben").isMember()).isTrue();
        }

        @Test
        void should_deleteGroups_andEndTheirMemberships() {
            var ben = createUser("ben");
            var sales = createSales(ben);

            scim.deleteGroup(sales);

            assertThat(authorization.membership(vault, "ben").isMember()).isFalse();
            assertThat(audit).contains("SCIM_GROUP_DELETED");
            assertThatThrownBy(() -> scim.group(sales)).isInstanceOf(ScimException.class);
        }

        @Test
        void should_ignoreMembersItDoesNotKnow() {
            var sales = createSales(UUID.randomUUID());

            scim.patchGroup(sales, json(patch("[{'op':'add','path':'members','value':[{'value':'not-a-uuid'}]}]")));

            assertThat(scim.members(sales)).isEmpty();
        }
    }
}
