package de.tstieh.stoneintelligence.platform.ai;

import java.time.Duration;
import java.time.Instant;
import de.tstieh.stoneintelligence.platform.audit.AuditService;
import de.tstieh.stoneintelligence.platform.sync.relay.SnapshotStore;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.sync.yjs.YjsBridge;
import de.tstieh.stoneintelligence.platform.vault.FolderRegistry;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
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
                                         AiServiceDirectory services, AiChangeSetRepository changeSets,
                                         de.tstieh.stoneintelligence.platform.files.FileService files) {
        return new AiWriteService(notes, snapshots, relay, yjs, announcements, folders, audit::record, services, changeSets,
            Instant::now, files);
    }

    @Bean
    public AiJobService aiJobService(AiJobRepository jobs, AiServiceDirectory services, @Lazy AiWriteService ai) {
        return new AiJobService(jobs, () -> services,
            (vaultId, service, requestedBy, label) -> ai.startChangeSet(vaultId, service, requestedBy, label).id(),
            (vaultId, changeSetId, actor) -> ai.revert(vaultId, changeSetId, actor), Instant::now);
    }

    @Bean
    public LinkingService linkingService(LinkingSettingsRepository settings, AiJobService jobs, @Lazy AiWriteService ai,
                                         VaultAccessGuard access, NoteRepository notes, AiServiceDirectory services) {
        return new LinkingService(settings, jobs, ai, access, notes, services, Instant::now);
    }

    @Bean
    public NightlyLinking nightlyLinking(LinkingService linking) {
        return new NightlyLinking(linking);
    }

    /** ADR 0012: jede Nacht um 02:00 (deutsche Zeit) je eingeschaltetem Vault ein Verlinkungslauf. */
    public static class NightlyLinking {
        private final LinkingService linking;

        NightlyLinking(LinkingService linking) {
            this.linking = linking;
        }

        @Scheduled(cron = "0 0 2 * * *", zone = "Europe/Berlin")
        public void run() {
            var started = linking.startNightlyRuns();
            if (started > 0) {
                LOG.info("{} naechtliche Verlinkungslaeufe gestartet", started);
            }
        }
    }

    @Bean
    public AiCapacityBoard aiCapacityBoard(AiServiceDirectory services) {
        return new AiCapacityBoard(() -> services, Instant::now);
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
