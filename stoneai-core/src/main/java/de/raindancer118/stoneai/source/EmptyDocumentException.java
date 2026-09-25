package de.raindancer118.stoneai.source;

import java.nio.file.Path;

/** Raised when a document could be opened but holds no usable text. */
public class EmptyDocumentException extends RuntimeException {

    public EmptyDocumentException(Path file) {
        super(file.getFileName() + " enthält keinen lesbaren Text");
    }

    /** With the detail of what was tried - shown to whoever uploaded the document. */
    public EmptyDocumentException(Path file, String detail) {
        super(file.getFileName() + " enthält keinen lesbaren Text – " + detail);
    }
}
