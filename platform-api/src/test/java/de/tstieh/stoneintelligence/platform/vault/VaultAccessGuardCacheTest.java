package de.tstieh.stoneintelligence.platform.vault;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.platform.identity.AccessGrant;
import de.tstieh.stoneintelligence.platform.identity.AccessVersions;
import de.tstieh.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.tstieh.stoneintelligence.platform.identity.FakeAuthorizationRepository;
import de.tstieh.stoneintelligence.platform.identity.GrantScope;
import de.tstieh.stoneintelligence.platform.identity.GrantTarget;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VaultAccessGuardCacheTest {

    private final FakeAuthorizationRepository authorization = new FakeAuthorizationRepository();
    private final FakeNoteRepository notes = new FakeNoteRepository();
    private final AtomicInteger grantLoads = new AtomicInteger();
    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(notes) {
        @Override
        public List<AccessGrant> list(VaultId vaultId) {
            grantLoads.incrementAndGet();
            return super.list(vaultId);
        }
    };
    private final AtomicLong version = new AtomicLong();
    private final AtomicInteger versionReads = new AtomicInteger();
    private final AtomicLong now = new AtomicLong();
    private final AccessVersions versions = vault -> {
        versionReads.incrementAndGet();
        return version.get();
    };
    private final VaultAccessGuard guard = new VaultAccessGuard(authorization, grants, versions, now::get);
    private final VaultId vaultId = VaultId.newId();

    @BeforeEach
    void setUp() {
        var role = authorization.createRole(vaultId, "lesen", Set.of(Permission.READ));
        var group = authorization.createGroup(vaultId, "alle");
        authorization.assignRole(group.id(), role.id());
        authorization.addMember(group.id(), "ben");
        notes.create(vaultId, "Team/plan.md", NoteLevel.of(1), "tom");
    }

    @Test
    void should_loadTheGrantsOnce_forManyChecks() {
        for (var i = 0; i < 1000; i++) {
            guard.accessAt(vaultId, "ben", "Team/plan.md");
        }

        assertThat(grantLoads).hasValue(1);
        assertThat(versionReads.get()).isLessThanOrEqualTo(1);
    }

    @Test
    void should_seeANewGrant_assoonAsTheAccessVersionChanges() {
        assertThat(guard.accessAt(vaultId, "ben", "Team/plan.md").allows(Permission.READ)).isTrue();
        grants.put(vaultId, GrantTarget.folder("Team"), GrantScope.user("ben"), Set.of(), "tom");
        version.incrementAndGet();
        now.addAndGet(VaultAccessGuard.VERSION_CHECK_NANOS + 1);

        assertThat(guard.accessAt(vaultId, "ben", "Team/plan.md").allows(Permission.READ)).isFalse();
        assertThat(grantLoads).hasValue(2);
    }

    @Test
    void should_checkTheVersionAtMostOncePerInterval() {
        guard.accessAt(vaultId, "ben", "Team/plan.md");
        now.addAndGet(VaultAccessGuard.VERSION_CHECK_NANOS / 2);
        guard.accessAt(vaultId, "ben", "Team/plan.md");

        assertThat(versionReads.get()).isLessThanOrEqualTo(1);
    }

    // Aenderungen ueber diese Instanz sollen sofort wirken, nicht erst nach dem Intervall.
    @Test
    void should_forgetAVault_whenToldItsAccessChanged() {
        guard.accessAt(vaultId, "ben", "Team/plan.md");
        grants.put(vaultId, GrantTarget.folder("Team"), GrantScope.user("ben"), Set.of(), "tom");

        guard.accessChanged(vaultId);

        assertThat(guard.accessAt(vaultId, "ben", "Team/plan.md").allows(Permission.READ)).isFalse();
    }

    @Test
    void should_cacheMemberships_perVersion() {
        var loads = new AtomicInteger();
        var counting = new FakeAuthorizationRepository() {
            @Override
            public de.tstieh.stoneintelligence.platform.identity.Membership membership(VaultId vault, String subject) {
                loads.incrementAndGet();
                return authorization.membership(vault, subject);
            }
        };
        var cached = new VaultAccessGuard(counting, grants, versions, now::get);

        for (var i = 0; i < 100; i++) {
            cached.requireMember(vaultId, "ben");
        }

        assertThat(loads).hasValue(1);
    }
}
