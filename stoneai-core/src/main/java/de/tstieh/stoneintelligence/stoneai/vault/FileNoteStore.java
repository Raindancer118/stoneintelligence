package de.tstieh.stoneintelligence.stoneai.vault;

import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import de.tstieh.stoneintelligence.stoneai.protection.ProtectionPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The vault as a folder of Markdown files - the CLI's store. */
final class FileNoteStore implements NoteStore {

    @Override
    public boolean exists(Path file) {
        return Files.exists(file);
    }

    @Override
    public String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Override
    public Path writeAttachment(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, content);
        return file;
    }

    @Override
    public void write(Path file, String content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".stoneai", ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public List<IndexedNote> indexable(StoneAiConfig config, ProtectionPolicy protection) throws IOException {
        List<IndexedNote> notes = new ArrayList<>();
        Path root = config.vault().resolvedPath();
        if (!Files.isDirectory(root)) {
            return notes;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".md"))
                    .filter(file -> !protection.inspectPath(file).isProtected())
                    .forEach(file -> {
                        String content = readUnchecked(file);
                        if (!protection.inspectText(content).isProtected()) {
                            notes.add(IndexedNote.fromContent(file, content));
                        }
                    });
        }
        return notes;
    }

    @Override
    public boolean localFiles() {
        return true;
    }

    private String readUnchecked(Path file) {
        try {
            return read(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
