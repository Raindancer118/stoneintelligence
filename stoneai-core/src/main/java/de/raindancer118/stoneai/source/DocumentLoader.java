package de.raindancer118.stoneai.source;

import java.io.IOException;
import java.nio.file.Path;

/** Turns one file into a {@link SourceDocument}. */
public interface DocumentLoader {

    boolean supports(Path file);

    SourceDocument load(Path file) throws IOException;
}
