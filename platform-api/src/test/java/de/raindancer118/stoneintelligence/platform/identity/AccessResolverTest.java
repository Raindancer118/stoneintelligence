package de.raindancer118.stoneintelligence.platform.identity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static de.raindancer118.stoneintelligence.platform.identity.Permission.CREATE;
import static de.raindancer118.stoneintelligence.platform.identity.Permission.DELETE;
import static de.raindancer118.stoneintelligence.platform.identity.Permission.MANAGE;
import static de.raindancer118.stoneintelligence.platform.identity.Permission.READ;
import static de.raindancer118.stoneintelligence.platform.identity.Permission.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

class AccessResolverTest {

    private static final VaultId VAULT = VaultId.of(UUID.randomUUID());
    private static final UUID TEAM = UUID.randomUUID();
    private static final UUID OTHERS = UUID.randomUUID();
    private static final NoteId PLAN = NoteId.of(UUID.randomUUID());

    private static final Membership EDITOR = new Membership("anna", Set.of(TEAM), Set.of(READ, WRITE, CREATE, DELETE));
    private static final Membership READER = new Membership("ben", Set.of(TEAM), Set.of(READ));
    private static final Membership OWNER = new Membership("tom", Set.of(TEAM), Set.of(READ, WRITE, CREATE, DELETE, MANAGE));

    private static AccessGrant folder(String path, GrantScope scope, Set<Permission> permissions) {
        return new AccessGrant(UUID.randomUUID(), VAULT, GrantTarget.folder(path), scope, permissions);
    }

    private static AccessGrant entry(NoteId id, String path, GrantScope scope, Set<Permission> permissions) {
        return new AccessGrant(UUID.randomUUID(), VAULT, GrantTarget.entry(id, path), scope, permissions);
    }

    @Test
    void should_useVaultPermissions_when_noGrantMatches() {
        var access = AccessResolver.resolve(EDITOR, List.of(folder("Other", GrantScope.everyone(), Set.of())), "Projects/plan.md");

        assertThat(access.permissions()).containsExactlyInAnyOrder(READ, WRITE, CREATE, DELETE);
        assertThat(access.source()).isNull();
    }

    @Test
    void should_grantNothing_when_subjectIsNoMember_evenIfEveryoneMayRead() {
        var outsider = new Membership("eve", Set.of(), Set.of());
        var grants = List.of(folder("Public", GrantScope.everyone(), Set.of(READ)));

        assertThat(AccessResolver.resolve(outsider, grants, "Public/a.md").permissions()).isEmpty();
    }

    @Test
    void should_giveMoreRights_than_theVaultRole() {
        var grants = List.of(entry(PLAN, "Projects/plan.md", GrantScope.user("ben"), Set.of(READ, WRITE)));

        assertThat(AccessResolver.resolve(READER, grants, "Projects/plan.md").permissions()).containsExactlyInAnyOrder(READ, WRITE);
        assertThat(AccessResolver.resolve(READER, grants, "Projects/other.md").permissions()).containsExactly(READ);
    }

    @Test
    void should_takeRightsAway_when_grantIsEmpty() {
        var grants = List.of(folder("Secret", GrantScope.user("anna"), Set.of()));

        assertThat(AccessResolver.resolve(EDITOR, grants, "Secret/deep/a.md").permissions()).isEmpty();
        assertThat(AccessResolver.resolve(EDITOR, grants, "Secretive/a.md").permissions()).contains(READ);
    }

    @Test
    void should_restoreVaultRights_when_deeperGrantInheritsVault() {
        var grants = List.of(
            folder("Secret", GrantScope.everyone(), Set.of()),
            folder("Secret/Shared", GrantScope.everyone(), null));

        assertThat(AccessResolver.resolve(EDITOR, grants, "Secret/a.md").permissions()).isEmpty();
        assertThat(AccessResolver.resolve(EDITOR, grants, "Secret/Shared/a.md").permissions()).contains(READ, WRITE);
    }

    @Nested
    class Specificity {

        @Test
        void should_preferTheDeepestFolder_overTheScope() {
            var grants = List.of(
                folder("A", GrantScope.user("anna"), Set.of(READ, WRITE)),
                folder("A/B", GrantScope.everyone(), Set.of(READ)));

            assertThat(AccessResolver.resolve(EDITOR, grants, "A/B/c.md").permissions()).containsExactly(READ);
            assertThat(AccessResolver.resolve(EDITOR, grants, "A/c.md").permissions()).containsExactlyInAnyOrder(READ, WRITE);
        }

        @Test
        void should_preferTheEntry_overAnyFolder() {
            var grants = List.of(
                folder("Projects", GrantScope.user("anna"), Set.of()),
                entry(PLAN, "Projects/plan.md", GrantScope.everyone(), Set.of(READ)));

            assertThat(AccessResolver.resolve(EDITOR, grants, "Projects/plan.md").permissions()).containsExactly(READ);
        }

        @Test
        void should_preferPerson_overGroup_overEveryone_onTheSameLevel() {
            var grants = List.of(
                folder("A", GrantScope.everyone(), Set.of()),
                folder("A", GrantScope.group(TEAM), Set.of(READ)),
                folder("A", GrantScope.user("anna"), Set.of(READ, WRITE)));

            assertThat(AccessResolver.resolve(EDITOR, grants, "A/x.md").permissions()).containsExactlyInAnyOrder(READ, WRITE);
            assertThat(AccessResolver.resolve(READER, grants, "A/x.md").permissions()).containsExactly(READ);
            var stranger = new Membership("cleo", Set.of(OTHERS), Set.of(READ));
            assertThat(AccessResolver.resolve(stranger, grants, "A/x.md").permissions()).isEmpty();
        }

        @Test
        void should_unionTheRightsOfSeveralGroups_onTheSameLevel() {
            var inBoth = new Membership("dora", Set.of(TEAM, OTHERS), Set.of());
            var grants = List.of(
                folder("A", GrantScope.group(TEAM), Set.of(READ)),
                folder("A", GrantScope.group(OTHERS), Set.of(WRITE)));

            assertThat(AccessResolver.resolve(inBoth, grants, "A/x.md").permissions()).containsExactlyInAnyOrder(READ, WRITE);
        }

        @Test
        void should_ignoreGrantsOfGroupsTheSubjectIsNotIn() {
            var grants = List.of(folder("A", GrantScope.group(OTHERS), Set.of()));

            assertThat(AccessResolver.resolve(EDITOR, grants, "A/x.md").permissions()).contains(READ);
        }
    }

    @Nested
    class Folders {

        @Test
        void should_applyAFolderGrant_toTheFolderItself_whenAskedWithTrailingSlash() {
            var grants = List.of(folder("A/B", GrantScope.user("anna"), Set.of(READ)));

            assertThat(AccessResolver.resolve(EDITOR, grants, "A/B/").permissions()).containsExactly(READ);
            assertThat(AccessResolver.resolve(EDITOR, grants, "A/").permissions()).contains(WRITE);
        }

        @Test
        void should_notApplyAFolderGrant_toANoteWithTheSameName() {
            var grants = List.of(folder("A/B", GrantScope.user("anna"), Set.of()));

            assertThat(AccessResolver.resolve(EDITOR, grants, "A/B").permissions()).contains(READ);
        }

        @Test
        void should_notApplyAnEntryGrant_toAFolderPath() {
            var grants = List.of(entry(PLAN, "A/plan.md", GrantScope.user("anna"), Set.of()));

            assertThat(AccessResolver.resolve(EDITOR, grants, "A/plan.md/").permissions()).contains(READ);
        }

        @Test
        void should_treatTheEmptyFolder_asTheWholeVault_belowEveryDeeperGrant() {
            var grants = List.of(
                folder("", GrantScope.user("anna"), Set.of()),
                folder("Open", GrantScope.user("anna"), Set.of(READ)));

            assertThat(AccessResolver.resolve(EDITOR, grants, "a.md").permissions()).isEmpty();
            assertThat(AccessResolver.resolve(EDITOR, grants, "Open/a.md").permissions()).containsExactly(READ);
        }

        @Test
        void should_ignoreLeadingAndTrailingSlashes_inGrantPaths() {
            var grants = List.of(folder("/A/B/", GrantScope.user("anna"), Set.of()));

            assertThat(AccessResolver.resolve(EDITOR, grants, "A/B/c.md").permissions()).isEmpty();
        }
    }

    @Test
    void should_keepManage_forVaultManagers_evenWhenAGrantHidesTheEntry() {
        var grants = List.of(folder("Private", GrantScope.user("tom"), Set.of()));

        var access = AccessResolver.resolve(OWNER, grants, "Private/diary.md");

        assertThat(access.permissions()).containsExactly(MANAGE);
        assertThat(access.allows(READ)).isFalse();
    }

    @Test
    void should_nameTheGrantThatDecided() {
        var decisive = folder("A/B", GrantScope.user("anna"), Set.of(READ));
        var grants = List.of(folder("A", GrantScope.user("anna"), Set.of()), decisive);

        assertThat(AccessResolver.resolve(EDITOR, grants, "A/B/c.md").source()).isEqualTo(decisive);
    }
}
