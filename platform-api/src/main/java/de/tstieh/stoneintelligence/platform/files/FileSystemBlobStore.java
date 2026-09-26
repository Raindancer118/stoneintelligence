package de.tstieh.stoneintelligence.platform.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Datei-Bytes als Dateien unter {@code <root>/<aa>/<bb>/<sha256>} - gehostet auf der Storage Box
 * (CIFS). Geschrieben wird in eine temporaere Datei im Zielverzeichnis und erst nach
 * vollstaendigem Lesen umbenannt: eine halb geschriebene Datei ist nie unter ihrem Hash sichtbar.
 * Liegt der Inhalt schon da, wird nur die temporaere Datei verworfen.
 */
public final class FileSystemBlobStore implements BlobStore {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String TEMP_PREFIX = ".upload-";

    private final Path root;

    public FileSystemBlobStore(Path root) {
        this(root, true);
    }

    /** {@code create=false}: das Verzeichnis muss schon existieren (eingehaengte Storage Box, nie ein leerer Ersatz). */
    FileSystemBlobStore(Path root, boolean create) {
        this.root = root;
        if (create) {
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new UncheckedIOException("Dateispeicher " + root + " nicht anlegbar", e);
            }
        }
    }

    @Override
    public StoredBlob put(InputStream content, long maxBytes) {
        requireAvailable();
        Path temp = null;
        try {
            Files.createDirectories(root.resolve("tmp"));
            temp = root.resolve("tmp").resolve(TEMP_PREFIX + UUID.randomUUID());
            var digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (var out = new DigestOutputStream(Files.newOutputStream(temp), digest)) {
                size = copyLimited(content, out, maxBytes);
            }
            var sha256 = HexFormat.of().formatHex(digest.digest());
            var target = pathOf(sha256);
            if (Files.exists(target) && Files.size(target) == size) {
                // Frisch halten: das Aufraeumen darf Bytes, auf die gleich eine Fassung verweist, nie fuer alt halten.
                Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(Instant.now()));
                return new StoredBlob(sha256, size);
            }
            Files.createDirectories(target.getParent());
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException concurrent) {
                // Parallel derselbe Inhalt - der andere Upload war schneller, Inhalt ist identisch.
            } catch (IOException atomicUnsupported) {
                if (!Files.exists(target)) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return new StoredBlob(sha256, size);
        } catch (IOException e) {
            throw new BlobStoreUnavailableException("Dateispeicher nicht beschreibbar", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Ein Rest in tmp/ stoert nicht und wird beim Aufraeumen entfernt.
                }
            }
        }
    }

    private static long copyLimited(InputStream in, java.io.OutputStream out, long maxBytes) throws IOException {
        var buffer = new byte[64 * 1024];
        long total = 0;
        for (int read; (read = in.read(buffer)) != -1; ) {
            total += read;
            if (total > maxBytes) {
                throw new BlobTooLargeException(maxBytes);
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    @Override
    public InputStream open(String sha256) {
        requireAvailable();
        try {
            return Files.newInputStream(pathOf(sha256));
        } catch (NoSuchFileException missing) {
            throw new BlobNotFoundException(sha256);
        } catch (IOException e) {
            throw new BlobStoreUnavailableException("Dateispeicher nicht lesbar", e);
        }
    }

    @Override
    public boolean exists(String sha256) {
        return Files.exists(pathOf(sha256));
    }

    @Override
    public int deleteUnreferenced(Set<String> referenced, Instant olderThan) {
        requireAvailable();
        var deleted = 0;
        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var name = file.getFileName().toString();
                var stale = Files.getLastModifiedTime(file).toInstant().isBefore(olderThan);
                if (stale && (name.startsWith(TEMP_PREFIX) || SHA256.matcher(name).matches() && !referenced.contains(name))) {
                    Files.deleteIfExists(file);
                    deleted += name.startsWith(TEMP_PREFIX) ? 0 : 1;
                }
            }
        } catch (IOException e) {
            throw new BlobStoreUnavailableException("Dateispeicher nicht aufraeumbar", e);
        }
        return deleted;
    }

    @Override
    public boolean available() {
        return Files.isDirectory(root) && Files.isWritable(root);
    }

    Path pathOf(String sha256) {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("kein SHA-256: " + sha256);
        }
        return root.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
    }

    private void requireAvailable() {
        if (!available()) {
            throw new BlobStoreUnavailableException("Dateispeicher " + root + " ist nicht verfügbar", null);
        }
    }
}
