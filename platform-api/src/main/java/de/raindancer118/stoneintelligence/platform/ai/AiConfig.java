package de.raindancer118.stoneintelligence.platform.ai;

import java.time.Duration;
import java.time.Instant;
import de.raindancer118.stoneintelligence.platform.audit.AuditService;
import de.raindancer118.stoneintelligence.platform.sync.relay.SnapshotStore;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.raindancer118.stoneintelligence.platform.sync.yjs.YjsBridge;
import de.raindancer118.stoneintelligence.platform.vault.FolderRegistry;
import de.raindancer118.stoneintelligence.platform.vault.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * KI-Dienste und Worker-Zugang ueber Umgebungsvariablen (auf dorn in der Stack-.env):
 * {@code STONEINTELLIGENCE_AI_SERVICES} (s. {@link AiServiceDirectory#parse}) und
 * {@code STONEINTELLIGENCE_AI_WORKER_TOKEN}. Ohne beides startet die Anwendung normal, nur ohne KI.
 * Die eingebettete Yjs-Laufzeit wird erst beim ersten KI-Aufruf geladen.
 */
@Configuration
public class AiConfig {

    /** So lange laesst sich eine KI-Aenderung rueckgaengig machen; danach werden die Textkopien geloescht. */
    static final Duration REVERT_WINDOW = Duration.ofDays(90);

    private static final Logger LOG = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public AiServiceDirectory aiServiceDirectory(@Value("${STONEINTELLIGENCE_AI_SERVICES:}") String services) {
        var directory = AiServiceDirectory.parse(services);
        LOG.info("KI-Dienste: {}", directory.all().isEmpty() ? "keine" : directory.all());
        return directory;
    }

    @Bean
    public AiWorkerTokenFilter aiWorkerTokenFilter(@Value("${STONEINTELLIGENCE_AI_WORKER_TOKEN:}") String token) {
        var filter = new AiWorkerTokenFilter(token);
        if (!filter.enabled() && !token.isBlank()) {
            LOG.warn("STONEINTELLIGENCE_AI_WORKER_TOKEN ist kuerzer als {} Zeichen - Worker-Zugang bleibt gesperrt",
                AiWorkerTokenFilter.MIN_TOKEN_LENGTH);
        }
        return filter;
    }

    /** Filter nur in der eigenen /internal-Kette, nicht zusaetzlich als globaler Servlet-Filter. */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<AiWorkerTokenFilter> aiWorkerTokenFilterRegistration(
            AiWorkerTokenFilter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean(destroyMethod = "close")
    @Lazy
    public YjsBridge yjsBridge() {
        return YjsBridge.load();
    }

    @Bean
    @Lazy
    public AiWriteService aiWriteService(NoteRepository notes, SnapshotStore snapshots, SyncRelayService relay, YjsBridge yjs,
                                         VaultAnnouncementService announcements, FolderRegistry folders, AuditService audit,
                                         AiServiceDirectory services, AiChangeSetRepository changeSets) {
        return new AiWriteService(notes, snapshots, relay, yjs, announcements, folders, audit::record, services, changeSets,
            Instant::now);
    }

    @Bean
    public AiChangeSetHousekeeping aiChangeSetHousekeeping(AiChangeSetRepository changeSets) {
        return new AiChangeSetHousekeeping(changeSets);
    }

    /** Taeglich: Change-Sets samt Textkopien nach Ablauf des Rueckgaengig-Zeitraums loeschen. */
    public static class AiChangeSetHousekeeping {
        private final AiChangeSetRepository changeSets;

        AiChangeSetHousekeeping(AiChangeSetRepository changeSets) {
            this.changeSets = changeSets;
        }

        @Scheduled(initialDelay = 120_000, fixedDelay = 24 * 60 * 60 * 1000)
        public void purge() {
            var purged = changeSets.purgeCreatedBefore(Instant.now().minus(REVERT_WINDOW));
            if (purged > 0) {
                LOG.info("{} KI-Aenderungen nach {} Tagen entfernt", purged, REVERT_WINDOW.toDays());
            }
        }
    }
}
