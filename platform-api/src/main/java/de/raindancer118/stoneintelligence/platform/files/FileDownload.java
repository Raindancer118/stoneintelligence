package de.raindancer118.stoneintelligence.platform.files;

import java.io.IOException;
import java.io.InputStream;
import de.raindancer118.stoneintelligence.platform.vault.Note;

/** Die aktuelle Fassung einer Datei zum Ausliefern - der Aufrufer schliesst sie. */
public record FileDownload(Note note, FileVersion version, InputStream content) implements AutoCloseable {

    @Override
    public void close() throws IOException {
        content.close();
    }
}
