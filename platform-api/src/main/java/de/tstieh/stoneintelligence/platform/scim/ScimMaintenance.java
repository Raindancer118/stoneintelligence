package de.tstieh.stoneintelligence.platform.scim;

import java.time.Instant;
import de.tstieh.stoneintelligence.platform.identity.BlockedSubjects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Geloeschte SCIM-Konten verschwinden nach {@link DirectoryStore#DELETED_RETENTION} ganz. */
@Component
public class ScimMaintenance {

    private static final Logger log = LoggerFactory.getLogger(ScimMaintenance.class);

    private final DirectoryStore directory;
    private final BlockedSubjects blocked;

    public ScimMaintenance(DirectoryStore directory, BlockedSubjects blocked) {
        this.directory = directory;
        this.blocked = blocked;
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 6 * 60 * 60 * 1000)
    public void purge() {
        var purged = directory.purgeDeletedBefore(Instant.now().minus(DirectoryStore.DELETED_RETENTION));
        if (purged > 0) {
            blocked.changed();
            log.info("Removed {} SCIM account(s) deleted more than {} ago", purged, DirectoryStore.DELETED_RETENTION);
        }
    }
}
