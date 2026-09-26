package de.tstieh.stoneintelligence.platform.vault;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Vertrag, den {@code FakeVaultRepository} und {@code JdbcVaultRepository} beide erfuellen. */
public abstract class VaultRepositoryContractTest {

    protected abstract VaultRepository repository();

    @Test
    void should_returnCreatedVault_when_foundById() {
        var repository = repository();

        var created = repository.create("my-vault");
        var found = repository.findById(created.id());

        assertThat(found).contains(created);
    }

    @Test
    void should_renameAVault_andIgnoreUnknownOnes() {
        var repository = repository();
        var created = repository.create("alt");

        assertThat(repository.rename(created.id(), "neu")).get().extracting(Vault::name).isEqualTo("neu");
        assertThat(repository.findById(created.id())).get().extracting(Vault::name).isEqualTo("neu");
        assertThat(repository.rename(de.tstieh.stoneintelligence.domain.id.VaultId.newId(), "x")).isEmpty();
    }

    @Test
    void should_beEmpty_when_vaultDoesNotExist() {
        var repository = repository();

        assertThat(repository.findById(de.tstieh.stoneintelligence.domain.id.VaultId.newId())).isEmpty();
    }

    @Test
    void should_assignDistinctIds_when_creatingMultipleVaults() {
        var repository = repository();

        var first = repository.create("vault-a");
        var second = repository.create("vault-b");

        assertThat(first.id()).isNotEqualTo(second.id());
    }
}
