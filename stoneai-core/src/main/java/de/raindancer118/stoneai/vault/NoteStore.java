package de.raindancer118.stoneai.vault;

import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.protection.ProtectionPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Where the vault's notes live. The CLI keeps them in files ({@link #files()}); a hosted vault
 * (StoneIntelligence) reaches them through an API. Paths are the ones the rest of the pipeline
 * already computes - below {@code vault.path} - so a hosted store simply treats that as a
 * virtual root.
 */
public interface NoteStore {

    boolean exists(Path file);

    String read(Path file) throws IOException;

    /**
     * @throws NoteWriteRefusedException when the store keeps this note away from the AI (for
     *                                   example because a person wrote it) - the run reports it
     *                                   and carries on
     */
    void write(Path file, String content) throws IOException;

    /**
     * Stores the original of a processed document (a PDF) so the source note can link it.
     *
     * @return where it was stored - a hosted vault may pick another name when {@code file} is taken
     * @throws NoteWriteRefusedException when the store will not keep it (too large, not allowed)
     */
    default Path writeAttachment(Path file, byte[] content) throws IOException {
        throw new NoteWriteRefusedException("dieser Speicher nimmt keine Anhänge auf");
    }

    /** Every note the index may know about; protected notes are left out. */
    List<IndexedNote> indexable(StoneAiConfig config, ProtectionPolicy protection) throws IOException;

    /**
     * Whether paths name files on this machine. Only then does a source note mention where the
     * original lies - for an upload that location is a temporary file nobody can open.
     */
    default boolean localFiles() {
        return false;
    }

    static NoteStore files() {
        return new FileNoteStore();
    }
}
