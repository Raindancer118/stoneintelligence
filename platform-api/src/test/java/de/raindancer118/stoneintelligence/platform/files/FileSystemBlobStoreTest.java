package de.raindancer118.stoneintelligence.platform.files;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemBlobStoreTest {

    @TempDir
    Path root;

    private FileSystemBlobStore store() {
        return new FileSystemBlobStore(root);
    }

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private long filesUnder(Path dir) throws IOException {
        try (var files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void should_storeUnderTheContentHash_andReadItBack() throws Exception {
        var stored = store().put(bytes("Hallo PDF"), 1000);

        assertThat(stored.sha256()).isEqualTo(sha256("Hallo PDF"));
        assertThat(stored.size()).isEqualTo(9);
        try (var in = store().open(stored.sha256())) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("Hallo PDF");
        }
    }

    // Gleicher Inhalt in mehreren Vaults oder Versionen belegt nur einmal Platz.
    @Test
    void should_keepIdenticalContentOnlyOnce() throws Exception {
        store().put(bytes("gleich"), 1000);
        store().put(bytes("gleich"), 1000);

        assertThat(filesUnder(root)).isEqualTo(1);
    }

    // Eine zu grosse Datei darf weder gespeichert werden noch Reste hinterlassen.
    @Test
    void should_refuseContentAboveTheLimit_withoutLeavingAnythingBehind() throws Exception {
        assertThatThrownBy(() -> store().put(bytes("0123456789"), 5)).isInstanceOf(BlobTooLargeException.class);

        assertThat(filesUnder(root)).isZero();
    }

    @Test
    void should_writeConcurrentUploadsOfTheSameContent_withoutCorruption() throws Exception {
        var content = "x".repeat(200_000);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<StoredBlob>>();
            for (var i = 0; i < 6; i++) {
                futures.add(executor.submit(() -> store().put(bytes(content), 1_000_000)));
            }
            for (var future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS).sha256()).isEqualTo(sha256(content));
            }
        }

        assertThat(filesUnder(root)).isEqualTo(1);
        try (var in = store().open(sha256(content))) {
            assertThat(in.readAllBytes()).hasSize(200_000);
        }
    }

    @Test
    void should_reportMissingContent_andRejectAnythingButAHash() {
        assertThatThrownBy(() -> store().open("a".repeat(64))).isInstanceOf(BlobNotFoundException.class);
        assertThatThrownBy(() -> store().open("../../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    // Unreferenzierte Bytes erst nach der Karenzzeit loeschen: ein Upload desselben Inhalts,
    // der gerade laeuft, ist noch in keiner Version eingetragen.
    @Test
    void should_deleteOnlyUnreferencedContent_olderThanTheGracePeriod() throws Exception {
        var keep = store().put(bytes("in Gebrauch"), 1000);
        var old = store().put(bytes("alt"), 1000);
        var fresh = store().put(bytes("gerade hochgeladen"), 1000);
        Files.setLastModifiedTime(store().pathOf(old.sha256()), java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        Files.setLastModifiedTime(store().pathOf(keep.sha256()), java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        var deleted = store().deleteUnreferenced(java.util.Set.of(keep.sha256()), Instant.now().minus(Duration.ofDays(1)));

        assertThat(deleted).isEqualTo(1);
        assertThat(store().exists(old.sha256())).isFalse();
        assertThat(store().exists(keep.sha256())).isTrue();
        assertThat(store().exists(fresh.sha256())).isTrue();
    }

    // Sonst koennte das Aufraeumen Bytes loeschen, auf die gerade eine neue Fassung verweist.
    @Test
    void should_renewTheAgeOfExistingContent_whenItIsStoredAgain() throws Exception {
        var stored = store().put(bytes("wieder da"), 1000);
        Files.setLastModifiedTime(store().pathOf(stored.sha256()), java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(2))));

        store().put(bytes("wieder da"), 1000);

        assertThat(store().deleteUnreferenced(java.util.Set.of(), Instant.now().minus(Duration.ofDays(1)))).isZero();
        assertThat(store().exists(stored.sha256())).isTrue();
    }

    @Test
    void should_failClearly_whenTheStorageIsUnavailable() {
        var gone = new FileSystemBlobStore(root.resolve("nicht-gemountet"), false);

        assertThatThrownBy(() -> gone.put(bytes("x"), 10)).isInstanceOf(BlobStoreUnavailableException.class);
        assertThat(List.of(gone.available())).containsExactly(false);
    }
}
