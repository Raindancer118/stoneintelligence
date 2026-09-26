package de.tstieh.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.link.LinkText;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.NoteNotFoundException;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verlinkung eines Vaults (ADR 0012): Einstellungen, Laeufe (von Hand und naechtlich) und das
 * Setzen der Links, das der Worker anfragt. Ein Lauf handelt immer mit den Rechten einer Person -
 * wer ihn von Hand startet bzw. wer die naechtliche Verlinkung zuletzt eingestellt hat.
 */
public class LinkingService {

    private static final Logger LOG = LoggerFactory.getLogger(LinkingService.class);

    /** Was sich an den Einstellungen aendern laesst; {@code service == null}: bisheriger bzw. erster eingerichteter Dienst. */
    public record Change(boolean enabled, boolean linkHumanNotes, Integer maxLinksPerNote, String service) {
    }

    private final LinkingSettingsRepository settings;
    private final AiJobService jobs;
    private final AiWriteService ai;
    private final VaultAccessGuard access;
    private final NoteRepository notes;
    private final AiServiceDirectory services;
    private final Supplier<Instant> clock;

    public LinkingService(LinkingSettingsRepository settings, AiJobService jobs, AiWriteService ai, VaultAccessGuard access,
                          NoteRepository notes, AiServiceDirectory services, Supplier<Instant> clock) {
        this.settings = settings;
        this.jobs = jobs;
        this.ai = ai;
        this.access = access;
        this.notes = notes;
        this.services = services;
        this.clock = clock;
    }

    public LinkingSettings settings(VaultId vaultId, String actor) {
        access.requireMember(vaultId, actor);
        return current(vaultId);
    }

    /** Aendern darf, wer den Vault verwaltet; der naechtliche Lauf handelt danach in seinem Namen. */
    public LinkingSettings update(VaultId vaultId, String actor, Change change) {
        access.require(vaultId, actor, Permission.MANAGE);
        var before = current(vaultId);
        var service = serviceId(change.service() != null ? change.service() : before.service());
        var next = new LinkingSettings(vaultId, change.enabled(), before.mode(), change.linkHumanNotes(), change.maxLinksPerNote(),
            service, actor, before.lastRunAt(), clock.get());
        settings.save(next);
        return current(vaultId);
    }

    /** "Jetzt verlinken": im Namen der Person, die es anstoesst - sie braucht Schreibrecht im Vault. */
    public AiJob runNow(VaultId vaultId, String actor) {
        access.require(vaultId, actor, Permission.WRITE);
        return jobs.startLinking(vaultId, actor, serviceId(current(vaultId).service()));
    }

    /** Naechtlicher Lauf fuer jeden eingeschalteten Vault; liefert, wie viele gestartet wurden. */
    public int startNightlyRuns() {
        var started = 0;
        for (var vault : settings.enabled()) {
            try {
                access.require(vault.vaultId(), vault.requestedBy(), Permission.WRITE);
                jobs.startLinking(vault.vaultId(), vault.requestedBy(), serviceId(vault.service()));
                started++;
            } catch (RuntimeException skipped) {
                // Wer die Verlinkung eingestellt hat, darf nicht mehr schreiben, oder es laeuft noch ein Lauf.
                LOG.info("Verlinkung fuer Vault {} uebersprungen: {}", vault.vaultId().value(), skipped.getMessage());
            }
        }
        return started;
    }

    /** Vom Worker beim Abschluss eines Laufs - merkt sich, wann zuletzt verlinkt wurde. */
    public void finished(UUID jobId) {
        var job = jobs.running(jobId);
        if (job.kind() == AiJob.Kind.LINKING) {
            if (settings.find(job.vaultId()).isEmpty()) {
                // Von Hand verlinkt, ohne je etwas eingestellt zu haben: die (ausgeschalteten) Standards festhalten.
                settings.save(LinkingSettings.defaults(job.vaultId(), job.requestedBy(), clock.get()));
            }
            settings.markRun(job.vaultId(), clock.get());
        }
    }

    /** Setzt Links in einer Notiz - nur, wo die Person hinter dem Lauf schreiben und die Ziele lesen darf. */
    public List<LinkText.Insertion> link(VaultId vaultId, UUID changeSetId, NoteId noteId, List<AiWriteService.LinkRequest> requests) {
        var changeSet = ai.changeSet(vaultId, changeSetId).orElseThrow(() -> new AiWriteRefusedException("KI-Änderung nicht gefunden"));
        var note = notes.findById(vaultId, noteId).orElseThrow(() -> new NoteNotFoundException(vaultId, noteId));
        access.require(vaultId, changeSet.requestedBy(), Permission.WRITE, note.path());
        for (var request : requests) {
            var target = notes.findById(vaultId, request.target()).orElseThrow(() -> new NoteNotFoundException(vaultId, request.target()));
            access.require(vaultId, changeSet.requestedBy(), Permission.READ, target.path());
        }
        return ai.linkNote(vaultId, changeSetId, noteId, requests, current(vaultId));
    }

    private LinkingSettings current(VaultId vaultId) {
        return settings.find(vaultId).orElseGet(() -> LinkingSettings.defaults(vaultId, null, clock.get()));
    }

    private String serviceId(String wanted) {
        if (wanted == null) {
            return services.all().stream().findFirst().map(AiService::id)
                .orElseThrow(() -> new AiWriteRefusedException("Es ist kein KI-Dienst eingerichtet"));
        }
        return services.find(wanted).map(AiService::id)
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + wanted + " ist nicht eingerichtet"));
    }
}
