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

    /**
     * Was sich an den Einstellungen aendern laesst; {@code service == null}: bisheriger bzw. erster
     * eingerichteter Dienst, {@code mode == null}: bisheriger Modus.
     */
    public record Change(boolean enabled, boolean linkHumanNotes, Integer maxLinksPerNote, String service, LinkingSettings.Mode mode) {
    }

    /** Eine aehnliche Notiz fuer "Aehnliche Notizen": wo sie liegt, welcher Abschnitt passt, wie sehr (0..1). */
    public record SimilarNote(NoteId noteId, String path, String heading, double similarity) {
    }

    /** Hoechstens so viele Abschnitte je Notiz - schuetzt vor riesigen Anfragen. */
    static final int MAX_CHUNKS = 500;

    private final LinkingSettingsRepository settings;
    private final AiJobService jobs;
    private final AiWriteService ai;
    private final VaultAccessGuard access;
    private final NoteRepository notes;
    private final AiServiceDirectory services;
    private final NoteEmbeddingRepository embeddings;
    private final AiChangeSetRepository changeSets;
    private final Supplier<Instant> clock;

    public LinkingService(LinkingSettingsRepository settings, AiJobService jobs, AiWriteService ai, VaultAccessGuard access,
                          NoteRepository notes, AiServiceDirectory services, NoteEmbeddingRepository embeddings,
                          AiChangeSetRepository changeSets, Supplier<Instant> clock) {
        this.embeddings = embeddings;
        this.changeSets = changeSets;
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

    /** Fuer den Worker (kein Mensch dahinter - der Zugang ist das Worker-Token). */
    public LinkingSettings internalSettings(VaultId vaultId) {
        return current(vaultId);
    }

    /** Aendern darf, wer den Vault verwaltet; der naechtliche Lauf handelt danach in seinem Namen. */
    public LinkingSettings update(VaultId vaultId, String actor, Change change) {
        access.require(vaultId, actor, Permission.MANAGE);
        var before = current(vaultId);
        var service = serviceId(change.service() != null ? change.service() : before.service());
        var mode = change.mode() != null ? change.mode() : before.mode();
        var next = new LinkingSettings(vaultId, change.enabled(), mode, change.linkHumanNotes(), change.maxLinksPerNote(),
            service, actor, before.lastRunAt(), clock.get());
        settings.save(next);
        return current(vaultId);
    }

    /**
     * Einwilligung (Art. 49 Abs. 1 lit. a DSGVO), dass Auszuege eigener Notizen bei der KI-geprueften
     * Verlinkung an den KI-Anbieter gehen - jede Person nur fuer sich selbst, jederzeit widerrufbar.
     */
    public void setAiConsent(VaultId vaultId, String actor, boolean consent) {
        access.requireMember(vaultId, actor);
        settings.setAiConsent(vaultId, actor, consent, clock.get());
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

    /** Was schon indiziert ist - nur fuer Notizen, die der Lauf sehen darf. */
    public List<NoteEmbeddingRepository.State> embeddingStates(VaultId vaultId, UUID changeSetId) {
        var visible = visibleToRun(vaultId, changeSetId);
        return embeddings.states(vaultId).stream().filter(state -> visible.contains(state.noteId())).toList();
    }

    /** Vektoren einer Notiz speichern - nur, wenn die Person hinter dem Lauf sie lesen und der Dienst sie verarbeiten darf. */
    public void storeEmbeddings(VaultId vaultId, UUID changeSetId, NoteId noteId, String model, String contentHash,
                                List<NoteEmbeddingRepository.Chunk> chunks) {
        var changeSet = ai.changeSet(vaultId, changeSetId).orElseThrow(() -> new AiWriteRefusedException("KI-Änderung nicht gefunden"));
        var note = notes.findById(vaultId, noteId).orElseThrow(() -> new NoteNotFoundException(vaultId, noteId));
        access.require(vaultId, changeSet.requestedBy(), Permission.READ, note.path());
        var service = services.find(changeSet.service())
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + changeSet.service() + " ist nicht (mehr) eingerichtet"));
        if (!ai.mayProcess(service, note.level())) {
            throw new AiWriteRefusedException(service.name() + " darf " + note.path() + " nicht verarbeiten");
        }
        if (model == null || model.isBlank() || contentHash == null || contentHash.isBlank() || chunks.isEmpty() || chunks.size() > MAX_CHUNKS) {
            throw new AiWriteRefusedException("model, contentHash und 1 bis " + MAX_CHUNKS + " Abschnitte sind Pflicht");
        }
        for (var chunk : chunks) {
            if (chunk.vector() == null || chunk.vector().length != 384) {
                throw new AiWriteRefusedException("Jeder Abschnitt braucht einen Vektor mit 384 Werten");
            }
            for (var value : chunk.vector()) {
                if (!Float.isFinite(value)) {
                    throw new AiWriteRefusedException("Vektoren duerfen nur endliche Zahlen enthalten");
                }
            }
        }
        embeddings.replace(vaultId, noteId, model, contentHash, chunks);
    }

    /** Aehnliche Notizen fuer einen Lauf (Stufe 2) - nur unter denen, die er sehen darf. */
    public List<NoteEmbeddingRepository.Similar> similarForRun(VaultId vaultId, UUID changeSetId, NoteId noteId, int limit) {
        var visible = visibleToRun(vaultId, changeSetId);
        if (!visible.contains(noteId)) {
            throw new AiWriteRefusedException("Diese Notiz gehört nicht zum Lauf");
        }
        // Schon einmal verlinkt: nie wieder Kandidat - spart KI-Aufrufe und haelt entfernte Links entfernt.
        var linked = changeSets.linkedTargets(vaultId, noteId);
        return embeddings.similarTo(vaultId, noteId, Math.max(1, limit) * 3 + linked.size()).stream()
            .filter(similar -> visible.contains(similar.noteId()) && !linked.contains(similar.noteId()))
            .limit(Math.max(1, limit)).toList();
    }

    /** Abgelehnte Ziele einer Notiz samt den Text-Hashes, auf denen die Ablehnung beruhte. */
    public java.util.Map<NoteId, List<String>> rejections(VaultId vaultId, UUID changeSetId, NoteId noteId) {
        if (!visibleToRun(vaultId, changeSetId).contains(noteId)) {
            throw new AiWriteRefusedException("Diese Notiz gehört nicht zum Lauf");
        }
        return changeSets.rejectedTargets(vaultId, noteId);
    }

    /** Die KI fand den Link nicht sinnvoll - erst wieder fragen, wenn sich eine der Notizen aendert. */
    public void reject(VaultId vaultId, UUID changeSetId, NoteId source, NoteId target, String sourceHash, String targetHash) {
        var visible = visibleToRun(vaultId, changeSetId);
        if (!visible.contains(source) || !visible.contains(target) || sourceHash == null || targetHash == null) {
            throw new AiWriteRefusedException("Quelle und Ziel müssen zum Lauf gehören, beide Hashes sind Pflicht");
        }
        changeSets.rememberRejection(vaultId, source, target, sourceHash, targetHash, clock.get());
    }

    /** "Aehnliche Notizen" fuer Menschen: wer die Notiz lesen darf, sieht aehnliche, die er ebenfalls lesen darf. */
    public List<SimilarNote> similarNotes(VaultId vaultId, String actor, NoteId noteId, int limit) {
        access.requireMember(vaultId, actor);
        var note = notes.findById(vaultId, noteId).orElseThrow(() -> new NoteNotFoundException(vaultId, noteId));
        access.require(vaultId, actor, Permission.READ, note.path());
        var mayRead = access.accessChecker(vaultId, actor);
        var result = new java.util.ArrayList<SimilarNote>();
        for (var similar : embeddings.similarTo(vaultId, noteId, Math.max(1, limit) * 3)) {
            var other = notes.findById(vaultId, similar.noteId());
            if (other.isPresent() && mayRead.apply(other.get().path()).allows(Permission.READ)) {
                result.add(new SimilarNote(similar.noteId(), other.get().path(), similar.heading(), similar.similarity()));
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    /** Notizen, die ein Lauf kennen darf: fuer die Person dahinter lesbar und fuer den Dienst verarbeitbar. */
    private java.util.Set<NoteId> visibleToRun(VaultId vaultId, UUID changeSetId) {
        var changeSet = ai.changeSet(vaultId, changeSetId).orElseThrow(() -> new AiWriteRefusedException("KI-Änderung nicht gefunden"));
        var service = services.find(changeSet.service())
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + changeSet.service() + " ist nicht (mehr) eingerichtet"));
        var visible = new java.util.HashSet<NoteId>();
        String cursor = null;
        do {
            var page = notes.list(vaultId, cursor, 500);
            access.readableNotes(vaultId, changeSet.requestedBy(), page.notes()).stream()
                .filter(note -> ai.mayProcess(service, note.level()))
                .forEach(note -> visible.add(note.id()));
            cursor = page.complete() ? null : page.nextCursor().orElse(null);
        } while (cursor != null);
        return visible;
    }

    private LinkingSettings current(VaultId vaultId) {
        // Eine Einwilligung wirkt nur, solange die Person Mitglied ist (Datenschutzerklaerung).
        var consents = settings.aiConsents(vaultId).stream()
            .filter(subject -> access.membership(vaultId, subject).isMember())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return settings.find(vaultId).orElseGet(() -> LinkingSettings.defaults(vaultId, null, clock.get())).withAiConsents(consents);
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
