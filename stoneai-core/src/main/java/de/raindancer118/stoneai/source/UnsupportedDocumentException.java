package de.raindancer118.stoneai.source;

import java.nio.file.Path;

/** Raised when no loader can read a file — better than quietly skipping it. */
public class UnsupportedDocumentException extends RuntimeException {

    public UnsupportedDocumentException(Path file) {
        super("no loader can read " + file.getFileName() + " (supported: .pdf, .md, .markdown, .txt)");
    }
}
