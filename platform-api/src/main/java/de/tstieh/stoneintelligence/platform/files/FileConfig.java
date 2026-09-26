package de.tstieh.stoneintelligence.platform.files;

import java.nio.file.Path;
import java.time.Instant;
import de.tstieh.stoneintelligence.platform.audit.AuditService;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.vault.FolderRegistry;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Dateispeicher und Grenzen ueber Umgebungsvariablen (ADR 0009): {@code STONEINTELLIGENCE_FILE_STORAGE_DIR}
 * (gehostet: Storage Box, im Container unter /data/files eingehaengt), {@code _FILE_MAX_MB}, {@code _FILE_QUOTA_MB}.
 */
@Configuration
public class FileConfig {

    private static final Logger LOG = LoggerFactory.getLogger(FileConfig.class);

    @Bean
    public BlobStore blobStore(@Value("${STONEINTELLIGENCE_FILE_STORAGE_DIR:./data/files}") String directory) {
        var root = Path.of(directory).toAbsolutePath();
        LOG.info("Dateispeicher: {}", root);
        return new FileSystemBlobStore(root);
    }

    @Bean
    public FileService fileService(NoteRepository notes, FileVersionRepository versions, BlobStore blobs, FolderRegistry folders,
                                   VaultAnnouncementService announcements, AuditService audit,
                                   @Value("${STONEINTELLIGENCE_FILE_MAX_MB:200}") long maxFileMb,
                                   @Value("${STONEINTELLIGENCE_FILE_QUOTA_MB:5120}") long quotaMb) {
        return new FileService(notes, versions, blobs, folders, announcements, audit::record, FileLimits.ofMegabytes(maxFileMb, quotaMb),
            Instant::now);
    }

    @Bean
    public FileHousekeeping fileHousekeeping(FileService files) {
        return new FileHousekeeping(files);
    }

    /** Stuendlich: ersetzte Fassungen und unreferenzierte Bytes nach der Karenzzeit entfernen. */
    public static class FileHousekeeping {
        private final FileService files;

        FileHousekeeping(FileService files) {
            this.files = files;
        }

        @Scheduled(initialDelay = 300_000, fixedDelay = 60 * 60 * 1000)
        public void purge() {
            try {
                var purged = files.purge();
                if (purged > 0) {
                    LOG.info("{} ersetzte Datei-Fassungen/-Inhalte entfernt", purged);
                }
            } catch (BlobStoreUnavailableException unavailable) {
                LOG.warn("Dateispeicher nicht erreichbar - Aufräumen beim nächsten Lauf");
            }
        }
    }
}
