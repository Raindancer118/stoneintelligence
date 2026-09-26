package de.tstieh.stoneintelligence.platform.ai;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Vertrag fuer {@code FakeNoteEmbeddingRepository} und {@code JdbcNoteEmbeddingRepository}. */
public abstract class NoteEmbeddingRepositoryContractTest {

    protected abstract NoteEmbeddingRepository repository();

    protected abstract VaultId newVault();

    protected abstract NoteId newNote(VaultId vaultId, String path);

    protected abstract void deleteNote(VaultId vaultId, NoteId noteId);

    /** Ein normierter Vektor der Modellgroesse, der in Richtung {@code axis} zeigt (mit etwas {@code tilt} zur Achse 0). */
    static float[] vector(int axis, double tilt) {
        var v = new float[384];
        v[axis] = (float) Math.cos(tilt);
        v[0] += (float) Math.sin(tilt);
        var length = 0.0;
        for (var x : v) {
            length += x * x;
        }
        for (var i = 0; i < v.length; i++) {
            v[i] /= (float) Math.sqrt(length);
        }
        return v;
    }

    @Test
    void should_rememberModelAndSource_andReplaceAllChunks() {
        var repository = repository();
        var vault = newVault();
        var note = newNote(vault, "a.md");

        repository.replace(vault, note, "m1", "h1", List.of(new NoteEmbeddingRepository.Chunk(0, "A", vector(1, 0)),
            new NoteEmbeddingRepository.Chunk(1, "B", vector(2, 0))));
        repository.replace(vault, note, "m2", "h2", List.of(new NoteEmbeddingRepository.Chunk(0, "A", vector(3, 0))));

        assertThat(repository.states(vault)).containsExactly(new NoteEmbeddingRepository.State(note, "m2", "h2"));
        assertThat(repository.states(newVault())).isEmpty();
    }

    @Test
    void should_findTheMostSimilarNotes_bestFirst_withoutTheNoteItself() {
        var repository = repository();
        var vault = newVault();
        var source = newNote(vault, "Quelle.md");
        var close = newNote(vault, "Nah.md");
        var far = newNote(vault, "Fern.md");
        var otherVault = newVault();
        var foreign = newNote(otherVault, "Fremd.md");
        repository.replace(vault, source, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, "Teil", vector(5, 0))));
        repository.replace(vault, close, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, "X", vector(9, 0)),
            new NoteEmbeddingRepository.Chunk(1, "Passend", vector(5, 0.3))));
        repository.replace(vault, far, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, "Anders", vector(7, 1.2))));
        repository.replace(otherVault, foreign, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, "Gleich", vector(5, 0))));

        var similar = repository.similarTo(vault, source, 10);

        assertThat(similar).extracting(NoteEmbeddingRepository.Similar::noteId).containsExactly(close, far);
        assertThat(similar.getFirst().heading()).isEqualTo("Passend");
        assertThat(similar.getFirst().similarity()).isCloseTo(Math.cos(0.3), within(1e-3));
        assertThat(repository.similarTo(vault, source, 1)).hasSize(1);
    }

    @Test
    void should_forgetTheVectorsOfADeletedNote() {
        var repository = repository();
        var vault = newVault();
        var source = newNote(vault, "Quelle.md");
        var gone = newNote(vault, "Weg.md");
        repository.replace(vault, source, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, null, vector(5, 0))));
        repository.replace(vault, gone, "m", "h", List.of(new NoteEmbeddingRepository.Chunk(0, null, vector(5, 0.1))));

        deleteNote(vault, gone);

        assertThat(repository.similarTo(vault, source, 10)).isEmpty();
        assertThat(repository.states(vault)).extracting(NoteEmbeddingRepository.State::noteId).containsExactly(source);
    }
}
