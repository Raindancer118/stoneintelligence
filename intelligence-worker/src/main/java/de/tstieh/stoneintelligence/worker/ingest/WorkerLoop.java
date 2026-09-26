package de.tstieh.stoneintelligence.worker.ingest;

import java.io.UncheckedIOException;
import de.tstieh.stoneintelligence.worker.platform.PlatformApi;
import de.tstieh.stoneintelligence.worker.platform.PlatformRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Holt Jobs, solange welche da sind; danach wartet er bis zum naechsten Takt. */
public class WorkerLoop {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerLoop.class);

    private final PlatformApi platform;
    private final JobProcessor processor;
    private final CapacityReporter capacity;

    WorkerLoop(PlatformApi platform, JobProcessor processor, CapacityReporter capacity) {
        this.platform = platform;
        this.processor = processor;
        this.capacity = capacity;
    }

    @Scheduled(initialDelayString = "${STONEINTELLIGENCE_WORKER_POLL_MS:5000}", fixedDelayString = "${STONEINTELLIGENCE_WORKER_POLL_MS:5000}")
    public void drain() {
        try {
            for (var job = platform.claim(); job.isPresent(); job = platform.claim()) {
                LOG.info("Verarbeite {} ({}, Versuch {})", job.get().fileName(), job.get().service(), job.get().attempt());
                processor.process(job.get());
                capacity.report(job.get().service());
            }
        } catch (UncheckedIOException | PlatformRefusedException unreachable) {
            LOG.warn("platform-api nicht erreichbar: {}", unreachable.getMessage());
        }
    }
}
