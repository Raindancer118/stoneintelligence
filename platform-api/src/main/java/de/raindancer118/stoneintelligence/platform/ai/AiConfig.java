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
    public AiJobService aiJobService(AiJobRepository jobs, AiServiceDirectory services, @Lazy AiWriteService ai) {
        return new AiJobService(jobs, () -> services,
            (vaultId, service, requestedBy, label) -> ai.startChangeSet(vaultId, service, requestedBy, label).id(), Instant::now);
    }

    @Bean
    public AiHousekeeping aiHousekeeping(AiChangeSetRepository changeSets, AiJobRepository jobs) {
        return new AiHousekeeping(changeSets, jobs);
    }

    /** Taeglich: Change-Sets samt Textkopien und Job-Metadaten nach Ablauf des Rueckgaengig-Zeitraums loeschen. */
    public static class AiHousekeeping {
        private final AiChangeSetRepository changeSets;
        private final AiJobRepository jobs;

        AiHousekeeping(AiChangeSetRepository changeSets, AiJobRepository jobs) {
            this.changeSets = changeSets;
            this.jobs = jobs;
        }

        @Scheduled(initialDelay = 120_000, fixedDelay = 24 * 60 * 60 * 1000)
        public void purge() {
            var cutoff = Instant.now().minus(REVERT_WINDOW);
            var purged = changeSets.purgeCreatedBefore(cutoff) + jobs.purgeCreatedBefore(cutoff);
            if (purged > 0) {
                LOG.info("{} KI-Aenderungen/-Jobs nach {} Tagen entfernt", purged, REVERT_WINDOW.toDays());
            }
        }
    }
}
