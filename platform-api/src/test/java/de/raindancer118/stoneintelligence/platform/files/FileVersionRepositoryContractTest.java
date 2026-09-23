package de.raindancer118.stoneintelligence.platform.files;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gemeinsamer Vertrag fuer Fake- und Postgres-Implementierung der Datei-Versionen. */
public abstract class FileVersionRepositoryContractTest {

    protected abstract FileVersionRepository repository();

    /** Eine existierende Datei-Id in einem existierenden Vault. */
    protected abstract NoteId newFile(VaultId vaultId);

    protected abstract VaultId newVault();

    /** Entfernt die Datei selbst (so wie eine Loeschung ueber die API). */
    protected abstract void deleteFile(VaultId vaultId, NoteId noteId);

    private FileVersionRepository versions;
    private final Instant now = Instant.parse("2026-09-23T10:00:00Z");

    @BeforeEach
    void setUp() {
        versions = repository();
    }

    private static StoredBlob blob(char c, long size) {
        return new StoredBlob(String.valueOf(c).repeat(64), size);
    }

    @Test
    void should_countRevisionsUp_fromTheOneTheChangeIsBasedOn() {
        var vault = newVault();
        var file = newFile(vault);

        var first = versions.append(file, 0, blob('a', 10), "application/pdf", "tom", now);
        var second = versions.append(file, 1, blob('b', 20), "application/pdf", "anna", now.plusSeconds(1));

        assertThat(first.revision()).isEqualTo(1);
        assertThat(second.revision()).isEqualTo(2);
        assertThat(versions.current(file)).contains(second);
        assertThat(second).isEqualTo(new FileVersion(file, 2, "b".repeat(64), 20, "application/pdf", "anna", now.plusSeconds(1)));
    }

    // Zwei Geraete aendern dieselbe Fassung: nur eines gewinnt, das andere erfaehrt es (ADR 0009 Punkt 3).
    @Test
    void should_refuseAChangeBasedOnAnOutdatedRevision() {
        var file = newFile(newVault());
        versions.append(file, 0, blob('a', 10), "application/pdf", "tom", now);
        versions.append(file, 1, blob('b', 10), "application/pdf", "tom", now);

        assertThatThrownBy(() -> versions.append(file, 1, blob('c', 10), "application/pdf", "anna", now))
            .isInstanceOf(FileRevisionConflictException.class)
            .extracting(e -> ((FileRevisionConflictException) e).currentRevision()).isEqualTo(2L);
        assertThatThrownBy(() -> versions.append(newFile(newVault()), 3, blob('c', 10), "application/pdf", "anna", now))
            .isInstanceOf(FileRevisionConflictException.class);
    }

    @Test
    void should_giveTheCurrentVersionOfManyFilesAtOnce() {
        var vault = newVault();
        var one = newFile(vault);
        var two = newFile(vault);
        var empty = newFile(vault);
        versions.append(one, 0, blob('a', 1), "image/png", "tom", now);
        versions.append(two, 0, blob('b', 2), "image/png", "tom", now);
        versions.append(two, 1, blob('c', 3), "image/png", "tom", now);

        var current = versions.current(List.of(one, two, empty));

        assertThat(current).containsOnlyKeys(one, two);
        assertThat(current.get(two).revision()).isEqualTo(2);
    }

    @Test
    void should_measureTheSpaceAVaultUses_acrossAllKeptVersions() {
        var vault = newVault();
        var file = newFile(vault);
        versions.append(file, 0, blob('a', 100), "image/png", "tom", now);
        versions.append(file, 1, blob('b', 50), "image/png", "tom", now);
        versions.append(newFile(newVault()), 0, blob('c', 999), "image/png", "tom", now);

        assertThat(versions.usage(vault)).isEqualTo(150);
    }

    // Datensparsamkeit (ADR 0009 Punkt 6): nur die aktuelle Fassung bleibt, ersetzte nach der Karenzzeit weg.
    @Test
    void should_purgeReplacedVersions_afterTheGracePeriod_butNeverTheCurrentOne() {
        var vault = newVault();
        var file = newFile(vault);
        versions.append(file, 0, blob('a', 1), "image/png", "tom", now.minus(Duration.ofDays(3)));
        versions.append(file, 1, blob('b', 1), "image/png", "tom", now.minus(Duration.ofDays(2)));
        versions.append(file, 2, blob('c', 1), "image/png", "tom", now);
        var lonely = newFile(vault);
        versions.append(lonely, 0, blob('d', 1), "image/png", "tom", now.minus(Duration.ofDays(9)));

        assertThat(versions.purgeReplaced(now.minus(Duration.ofDays(1)))).isEqualTo(2);

        assertThat(versions.current(file)).get().extracting(FileVersion::revision).isEqualTo(3L);
        assertThat(versions.current(lonely)).isPresent();
        assertThat(versions.referencedHashes()).containsExactlyInAnyOrder("c".repeat(64), "d".repeat(64));
    }

    @Test
    void should_forgetTheVersionsOfDeletedFiles() {
        var vault = newVault();
        var file = newFile(vault);
        versions.append(file, 0, blob('a', 100), "image/png", "tom", now);

        deleteFile(vault, file);

        assertThat(versions.current(file)).isEmpty();
        assertThat(versions.usage(vault)).isZero();
        assertThat(versions.referencedHashes()).doesNotContain("a".repeat(64));
    }
}
