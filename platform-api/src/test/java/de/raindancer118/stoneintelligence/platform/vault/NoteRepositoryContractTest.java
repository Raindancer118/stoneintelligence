package de.raindancer118.stoneintelligence.platform.vault;

import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ein Vertrag, den jede {@link NoteRepository}-Implementierung erfuellen muss - egal ob
 * {@code FakeNoteRepository} (schnell, ohne DB) oder {@code JdbcNoteRepository} (echtes
 * Postgres via Testcontainers). Stil analog stoneai: Fakes ueber Ports statt Mockito fuer
 * Domaenenlogik.
 *
 * <p>Mandanten-Isolation ist hier bewusst genauso streng getestet wie die fachliche Logik:
 * {@code vaultId} ist die Mandantengrenze (Plan.md Abschnitt 8.2) - jede Operation muss sie
 * durchsetzen, nicht nur die Listing-Abfrage.
 */
public abstract class NoteRepositoryContractTest {

    protected abstract NoteRepository repository();

    @Nested
    class CreateAndFind {

        @Test
        void should_beFindableById_when_justCreated() {
            var repository = repository();
            var vaultId = VaultId.newId();

            var created = repository.create(vaultId, "foo/bar.md", NoteLevel.of(1), "tom");

            assertThat(repository.findById(vaultId, created.id())).contains(created);
        }

        @Test
        void should_beEmpty_when_idIsUnknown() {
            assertThat(repository().findById(VaultId.newId(), NoteId.newId())).isEmpty();
        }

        @Test
        void should_beEmpty_when_noteExistsButBelongsToADifferentVault() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var otherVaultId = VaultId.newId();
            var note = repository.create(vaultId, "mine.md", NoteLevel.of(1), "tom");

            assertThat(repository.findById(otherVaultId, note.id())).isEmpty();
        }
    }

    @Nested
    class Reconciliation {

        @Test
        void should_returnAllNotesAcrossPages_when_pageSizeSmallerThanNoteCount() {
            var repository = repository();
            var vaultId = VaultId.newId();
            for (int i = 0; i < 5; i++) {
                repository.create(vaultId, "note-" + i + ".md", NoteLevel.of(1), "tom");
            }

            var firstPage = repository.list(vaultId, null, 2);
            assertThat(firstPage.notes()).hasSize(2);
            assertThat(firstPage.complete()).isFalse();
            assertThat(firstPage.nextCursor()).isPresent();

            var secondPage = repository.list(vaultId, firstPage.nextCursor().get(), 2);
            assertThat(secondPage.epochId()).isEqualTo(firstPage.epochId());
            assertThat(secondPage.notes()).hasSize(2);
            assertThat(secondPage.complete()).isFalse();

            var thirdPage = repository.list(vaultId, secondPage.nextCursor().get(), 2);
            assertThat(thirdPage.notes()).hasSize(1);
            assertThat(thirdPage.complete()).isTrue();
            assertThat(thirdPage.nextCursor()).isEmpty();
        }

        @Test
        void should_markSinglePageComplete_when_allNotesFitInOnePage() {
            var repository = repository();
            var vaultId = VaultId.newId();
            repository.create(vaultId, "only.md", NoteLevel.of(1), "tom");

            var page = repository.list(vaultId, null, 10);

            assertThat(page.complete()).isTrue();
            assertThat(page.nextCursor()).isEmpty();
        }

        @Test
        void should_notLeakNotesFromOtherVaults_when_listing() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var otherVaultId = VaultId.newId();
            repository.create(vaultId, "mine.md", NoteLevel.of(1), "tom");
            repository.create(otherVaultId, "not-mine.md", NoteLevel.of(1), "someone-else");

            var page = repository.list(vaultId, null, 10);

            assertThat(page.notes()).extracting(Note::path).containsExactly("mine.md");
        }

        @Test
        void should_notSkipANoteCreatedDuringPagination_regardlessOfItsRandomId() {
            // Ordnung nach UUID statt nach einer monoton wachsenden Sequenznummer wuerde eine
            // waehrend der Pagination neu eingefuegte Note mit "kleinerer" UUID dauerhaft
            // uebergehen, obwohl die letzte Seite faelschlich complete=true meldet
            // (Fehlerklasse 2 - genau das soll die Epoch/Cursor-Konstruktion verhindern).
            var repository = repository();
            var vaultId = VaultId.newId();
            var first = repository.create(vaultId, "a.md", NoteLevel.of(1), "tom");
            var second = repository.create(vaultId, "b.md", NoteLevel.of(1), "tom");

            var firstPage = repository.list(vaultId, null, 1);
            assertThat(firstPage.complete()).isFalse();
            assertThat(firstPage.notes()).extracting(Note::id).containsExactly(first.id());

            // Wird "waehrend der Pagination" eingefuegt - egal, ob seine zufaellige UUID
            // lexikographisch kleiner oder groesser als die bereits gesehenen ist.
            var insertedDuringPagination = repository.create(vaultId, "c.md", NoteLevel.of(1), "tom");

            var secondPage = repository.list(vaultId, firstPage.nextCursor().get(), 10);

            assertThat(secondPage.complete()).isTrue();
            assertThat(secondPage.notes()).extracting(Note::id)
                .containsExactlyInAnyOrder(second.id(), insertedDuringPagination.id());
        }
    }

    @Nested
    class Delete {

        @Test
        void should_removeNoteFromFindById_when_deleted() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var note = repository.create(vaultId, "gone.md", NoteLevel.of(1), "tom");

            repository.delete(vaultId, note.id(), "op-1", "tom");

            assertThat(repository.findById(vaultId, note.id())).isEmpty();
        }

        @Test
        void should_removeNoteFromReconciliationListing_when_deleted() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var note = repository.create(vaultId, "gone.md", NoteLevel.of(1), "tom");

            repository.delete(vaultId, note.id(), "op-1", "tom");

            assertThat(repository.list(vaultId, null, 10).notes()).isEmpty();
        }

        @Test
        void should_returnSameTombstone_when_deleteCalledTwiceWithSameOperationId() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var note = repository.create(vaultId, "gone.md", NoteLevel.of(1), "tom");

            var first = repository.delete(vaultId, note.id(), "op-1", "tom");
            var second = repository.delete(vaultId, note.id(), "op-1", "tom");

            assertThat(second).isEqualTo(first);
        }

        @Test
        void should_assignIncreasingServerSequence_when_deletingMultipleNotes() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var first = repository.create(vaultId, "a.md", NoteLevel.of(1), "tom");
            var second = repository.create(vaultId, "b.md", NoteLevel.of(1), "tom");

            var firstTombstone = repository.delete(vaultId, first.id(), "op-a", "tom");
            var secondTombstone = repository.delete(vaultId, second.id(), "op-b", "tom");

            assertThat(secondTombstone.serverSequence()).isGreaterThan(firstTombstone.serverSequence());
        }

        @Test
        void should_notReturnAnotherNotesTombstone_when_operationIdIsReusedForADifferentNote() {
            // Idempotenz ist an (vaultId, noteId, operationId) gebunden, nicht nur an
            // (vaultId, operationId) - sonst koennte ein wiederverwendeter operationId-Wert die
            // Sync-Session/den Audit-Eintrag einer VOELLIG ANDEREN Note treffen.
            var repository = repository();
            var vaultId = VaultId.newId();
            var noteA = repository.create(vaultId, "a.md", NoteLevel.of(1), "tom");
            var noteB = repository.create(vaultId, "b.md", NoteLevel.of(1), "tom");

            var tombstoneForA = repository.delete(vaultId, noteA.id(), "shared-op-id", "tom");
            var tombstoneForB = repository.delete(vaultId, noteB.id(), "shared-op-id", "tom");

            assertThat(tombstoneForB.noteId()).isEqualTo(noteB.id());
            assertThat(tombstoneForB).isNotEqualTo(tombstoneForA);
        }

        @Test
        void should_rejectDelete_when_noteBelongsToADifferentVault() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var otherVaultId = VaultId.newId();
            var note = repository.create(vaultId, "not-yours.md", NoteLevel.of(1), "tom");

            assertThatThrownBy(() -> repository.delete(otherVaultId, note.id(), "op-1", "attacker"))
                .isInstanceOf(NoteNotFoundException.class);

            assertThat(repository.findById(vaultId, note.id())).contains(note);
        }
    }

    @Nested
    class Rename {

        @Test
        void should_updatePath_when_renamed() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var note = repository.create(vaultId, "old.md", NoteLevel.of(1), "tom");

            var renamed = repository.rename(vaultId, note.id(), "new.md");

            assertThat(renamed.path()).isEqualTo("new.md");
            assertThat(repository.findById(vaultId, note.id())).contains(renamed);
        }

        @Test
        void should_beReflectedInReconciliationListing_when_renamed() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var note = repository.create(vaultId, "old.md", NoteLevel.of(1), "tom");

            repository.rename(vaultId, note.id(), "new.md");

            assertThat(repository.list(vaultId, null, 10).notes()).extracting(Note::path).containsExactly("new.md");
        }

        @Test
        void should_preserveNoteId_when_renamed() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var note = repository.create(vaultId, "old.md", NoteLevel.of(1), "tom");

            var renamed = repository.rename(vaultId, note.id(), "new.md");

            assertThat(renamed.id()).isEqualTo(note.id());
        }

        @Test
        void should_rejectRename_when_noteBelongsToADifferentVault() {
            var repository = repository();
            var vaultId = VaultId.newId();
            var otherVaultId = VaultId.newId();
            var note = repository.create(vaultId, "not-yours.md", NoteLevel.of(1), "tom");

            assertThatThrownBy(() -> repository.rename(otherVaultId, note.id(), "hijacked.md"))
                .isInstanceOf(NoteNotFoundException.class);

            assertThat(repository.findById(vaultId, note.id())).contains(note);
        }
    }
}
