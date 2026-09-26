package de.tstieh.stoneintelligence.stoneai.vault;

import java.io.IOException;

/** The store will not let the AI change this note; the message says why. */
public class NoteWriteRefusedException extends IOException {

    public NoteWriteRefusedException(String reason) {
        super(reason);
    }
}
