package de.raindancer118.stoneintelligence.platform.vault;

import java.util.Set;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.platform.identity.AccessGrant;
import de.raindancer118.stoneintelligence.platform.identity.FakeAccessGrantRepository;
import de.raindancer118.stoneintelligence.platform.identity.GrantScope;
import de.raindancer118.stoneintelligence.platform.identity.GrantTarget;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FolderRegistryTest {

    private final FakeAccessGrantRepository grants = new FakeAccessGrantRepository(new FakeNoteRepository());
    private final FolderRegistry registry = new FolderRegistry(new FakeFolderRepository(), new VaultAnnouncementService(), grants);
    private final VaultId vaultId = VaultId.newId();

    @Test
    void should_takeTheFoldersGrantsAlong_whenRenamed_andDropThem_whenDeleted() {
        registry.create(vaultId, "Team/Intern", "tom");
        grants.put(vaultId, GrantTarget.folder("Team/Intern"), GrantScope.user("ben"), Set.of(), "tom");

        registry.rename(vaultId, "Team", "Firma", "tom");
        assertThat(grants.list(vaultId)).extracting(AccessGrant::target).containsExactly(GrantTarget.folder("Firma/Intern"));

        registry.delete(vaultId, "Firma");
        assertThat(grants.list(vaultId)).isEmpty();
    }
}
