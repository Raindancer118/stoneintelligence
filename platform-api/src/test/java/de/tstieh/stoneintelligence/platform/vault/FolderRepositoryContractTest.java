package de.tstieh.stoneintelligence.platform.vault;

import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Gemeinsamer Vertrag fuer Fake- und Postgres-Implementierung. */
public abstract class FolderRepositoryContractTest {

    protected abstract FolderRepository repository();

    /** Liefert einen existierenden Vault (Postgres braucht die Fremdschluessel-Zeile). */
    protected abstract VaultId newVault();

    private FolderRepository folders;
    private VaultId vaultId;

    @BeforeEach
    void setUp() {
        folders = repository();
        vaultId = newVault();
    }

    @Test
    void should_createAFolderWithAllItsParents_andReportOnlyNewOnes() {
        assertThat(folders.ensure(vaultId, "A/B/C", "tom")).containsExactly("A", "A/B", "A/B/C");
        assertThat(folders.ensure(vaultId, "A/B", "anna")).isEmpty();

        assertThat(folders.list(vaultId)).containsExactly("A", "A/B", "A/B/C");
        assertThat(folders.list(newVault())).isEmpty();
    }

    @Test
    void should_deleteAFolderWithEverythingBelow_butNotSimilarlyNamedSiblings() {
        folders.ensure(vaultId, "A/B/C", "tom");
        folders.ensure(vaultId, "A/BB", "tom");
        folders.ensure(vaultId, "A_B%/x", "tom");

        assertThat(folders.deleteTree(vaultId, "A/B")).isEqualTo(2);

        assertThat(folders.list(vaultId)).containsExactly("A", "A/BB", "A_B%", "A_B%/x");
    }

    @Test
    void should_moveAFolderWithEverythingBelow_andCreateTheNewParents() {
        folders.ensure(vaultId, "A/B/C", "tom");
        folders.ensure(vaultId, "A/BB", "tom");

        folders.renameTree(vaultId, "A/B", "Neu/Ort", "tom");

        assertThat(folders.list(vaultId)).containsExactly("A", "A/BB", "Neu", "Neu/Ort", "Neu/Ort/C");
    }

    @Test
    void should_mergeIntoAnExistingTarget() {
        folders.ensure(vaultId, "A/X", "tom");
        folders.ensure(vaultId, "B/X", "tom");

        folders.renameTree(vaultId, "A", "B", "tom");

        assertThat(folders.list(vaultId)).containsExactly("B", "B/X");
    }

    @Test
    void should_rememberWhoCreatedAFolder() {
        folders.ensure(vaultId, "A", "tom");
        folders.ensure(vaultId, "A/B", "ki:Gemini");

        assertThat(folders.creator(vaultId, "A")).contains("tom");
        assertThat(folders.creator(vaultId, "A/B")).contains("ki:Gemini");
        assertThat(folders.creator(vaultId, "C")).isEmpty();
        assertThat(folders.creator(newVault(), "A")).isEmpty();
    }

    @Test
    void should_keepFoldersOfOtherVaultsUntouched() {
        var other = newVault();
        folders.ensure(other, "A/B", "tom");
        folders.ensure(vaultId, "A/B", "tom");

        folders.deleteTree(vaultId, "A");
        folders.renameTree(vaultId, "A", "Z", "tom");

        assertThat(folders.list(other)).containsExactly("A", "A/B");
    }
}
