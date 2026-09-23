package de.raindancer118.stoneai.source;

import java.nio.file.Path;

/** Raised when a document could be opened but holds no usable text. */
public class EmptyDocumentException extends RuntimeException {

    public EmptyDocumentException(Path file) {
        super(file.getFileName() + " contains no readable text");
    }
}
