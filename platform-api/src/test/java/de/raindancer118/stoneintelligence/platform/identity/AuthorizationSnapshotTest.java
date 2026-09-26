package de.raindancer118.stoneintelligence.platform.identity;

import java.util.Set;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationSnapshotTest {

    private final FakeAuthorizationRepository repository = new FakeAuthorizationRepository();
    private final VaultId vaultId = VaultId.newId();
    private Role owner;
    private Role reader;
    private Group owners;
    private Group readers;

    @BeforeEach
    void vaultWithOneOwner() {
        owner = repository.createRole(vaultId, "owner", Set.of(Permission.READ, Permission.MANAGE));
        reader = repository.createRole(vaultId, "reader", Set.of(Permission.READ));
        owners = repository.createGroup(vaultId, "owners");
        readers = repository.createGroup(vaultId, "readers");
        repository.assignRole(owners.id(), owner.id());
        repository.assignRole(readers.id(), reader.id());
        repository.addMember(owners.id(), "tom");
        repository.addMember(readers.id(), "ben");
    }

    private AuthorizationSnapshot snapshot() {
        return AuthorizationSnapshot.of(repository, vaultId);
    }

    @Test
    void should_nameEveryoneWhoCanManage() {
        repository.addMember(owners.id(), "anna");

        assertThat(snapshot().managers()).containsExactlyInAnyOrder("tom", "anna");
    }

    @Test
    void should_seeTheLastManagerGo_forEveryKindOfChange() {
        var snapshot = snapshot();

        assertThat(snapshot.withoutMember(owners.id(), "tom").managers()).isEmpty();
        assertThat(snapshot.withoutSubject("tom").managers()).isEmpty();
        assertThat(snapshot.withoutAssignment(owners.id(), owner.id()).managers()).isEmpty();
        assertThat(snapshot.withRolePermissions(owner.id(), Set.of(Permission.READ)).managers()).isEmpty();
        assertThat(snapshot.withoutRole(owner.id()).managers()).isEmpty();
        assertThat(snapshot.withoutGroup(owners.id()).managers()).isEmpty();
    }

    @Test
    void should_keepTheManager_whenAnUnrelatedPartChanges() {
        var snapshot = snapshot();

        assertThat(snapshot.withoutMember(readers.id(), "ben").managers()).containsExactly("tom");
        assertThat(snapshot.withoutSubject("ben").managers()).containsExactly("tom");
        assertThat(snapshot.withoutRole(reader.id()).managers()).containsExactly("tom");
        assertThat(snapshot.withRolePermissions(reader.id(), Set.of(Permission.MANAGE)).managers()).containsExactlyInAnyOrder("tom", "ben");
    }
}
