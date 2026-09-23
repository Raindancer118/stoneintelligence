package de.raindancer118.stoneintelligence.platform.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import de.raindancer118.stoneintelligence.domain.notelevel.AgentProcessing;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevel;
import de.raindancer118.stoneintelligence.domain.notelevel.NoteLevelPolicyResolver;
import de.raindancer118.stoneintelligence.domain.notelevel.UnresolvedNoteLevelException;
import de.raindancer118.stoneintelligence.platform.sync.relay.SnapshotStore;
import de.raindancer118.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.raindancer118.stoneintelligence.platform.sync.relay.UpdateRecord;
import de.raindancer118.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.raindancer118.stoneintelligence.platform.sync.yjs.YjsBridge;
import de.raindancer118.stoneintelligence.platform.vault.FolderRegistry;
import de.raindancer118.stoneintelligence.platform.vault.Note;
import de.raindancer118.stoneintelligence.platform.vault.NotePaths;
import de.raindancer118.stoneintelligence.platform.vault.NoteRepository;

/**
 * Wie eine KI im Vault liest und schreibt (ADR 0008): ueber dieselben Wege wie Menschen - Yjs-Update
 * anhaengen, Live-Broadcast, Vault-Ankuendigung, Audit -, aber mit festen Grenzen:
 * <ul>
 *   <li>jeder {@link AiService} nur auf den Levels, die fuer ihn konfiguriert sind, nie 100/101;</li>
 *   <li>von Menschen angelegte Notizen werden nicht veraendert und ihre Pfade nicht belegt;</li>
 *   <li>jede Aenderung landet mit Text davor/danach in einem {@link AiChangeSet}, das sich
 *       konfliktgeprueft rueckgaengig machen laesst.</li>
 * </ul>
 */
public class AiWriteService {

    private static final int WRITE_ATTEMPTS = 5;

    private final NoteRepository notes;
    private final SnapshotStore snapshots;
    private final SyncRelayService relay;
    private final YjsBridge yjs;
    private final VaultAnnouncementService announcements;
    private final FolderRegistry folders;
    private final AiAuditRecorder audit;
    private final AiServiceDirectory services;
    private final AiChangeSetRepository changeSets;
    private final Supplier<Instant> clock;
    private final NoteLevelPolicyResolver levels = NoteLevelPolicyResolver.withDefaults();

    public AiWriteService(NoteRepository notes, SnapshotStore snapshots, SyncRelayService relay, YjsBridge yjs,
                          VaultAnnouncementService announcements, FolderRegistry folders, AiAuditRecorder audit,
                          AiServiceDirectory services,
                          AiChangeSetRepository changeSets, Supplier<Instant> clock) {
        this.notes = notes;
        this.snapshots = snapshots;
        this.relay = relay;
        this.yjs = yjs;
        this.announcements = announcements;
        this.folders = folders;
        this.audit = audit;
        this.services = services;
        this.changeSets = changeSets;
        this.clock = clock;
    }

    public AiChangeSet startChangeSet(VaultId vaultId, AiService service, String requestedBy, String label) {
        var configured = configured(service.id());
        return changeSets.create(vaultId, configured.id(), configured.agent(), requestedBy, label, clock.get());
    }

    public String readText(VaultId vaultId, NoteId noteId, AiService service) {
        var note = note(vaultId, noteId);
        requireLevel(configured(service.id()), note.level());
        return yjs.textOf(payloads(plainHistory(note)));
    }

    public WrittenNote createNote(VaultId vaultId, UUID changeSetId, String path, String text, NoteLevel level) {
        var changeSet = openChangeSet(vaultId, changeSetId);
        var service = configured(changeSet.service());
        requireLevel(service, level);
        if (!NotePaths.isValid(path)) {
            throw new AiWriteRefusedException("Ungültiger Notizpfad: " + path);
        }
        if (!notes.findByPath(vaultId, path).isEmpty()) {
            throw new AiWriteRefusedException("Unter " + path + " liegt schon eine Notiz");
        }
        var note = notes.create(vaultId, path, level, service.agent());
        audit.record(vaultId, note.id(), service.agent(), "note.created", Map.of("path", path, "changeSet", changeSetId.toString()));
        announcements.announceNoteCreated(vaultId, note.id(), path);
        folders.ensureParentsOf(vaultId, path, service.agent());
        try {
            writeText(note, text, service.agent());
        } catch (RuntimeException failure) {
            // Keine leere Hülle zurücklassen, die in keinem Change-Set steht und sich so nicht mehr
            // rückgängig machen ließe.
            remove(note, service.agent());
            throw failure;
        }
        changeSets.addChange(new AiChange(UUID.randomUUID(), changeSetId, note.id(), path, AiChange.Kind.CREATED, "", text, clock.get()));
        return new WrittenNote(note.id(), path);
    }

    public void updateNote(VaultId vaultId, UUID changeSetId, NoteId noteId, String text) {
        var changeSet = openChangeSet(vaultId, changeSetId);
        var service = configured(changeSet.service());
        var note = note(vaultId, noteId);
        requireLevel(service, note.level());
        if (!isAgent(note.createdBy())) {
            throw new AiWriteRefusedException("Notizen von Menschen verändert die KI nicht: " + note.path());
        }
        var before = writeText(note, text, service.agent());
        if (!before.equals(text)) {
            changeSets.addChange(new AiChange(UUID.randomUUID(), changeSetId, noteId, note.path(), AiChange.Kind.UPDATED,
                before, text, clock.get()));
        }
    }

    /**
     * Macht ein Change-Set rückgängig, neueste Änderung zuerst. Eine Notiz wird nur angefasst, wenn
     * ihr Text noch genau dem KI-Stand entspricht - was seitdem jemand geändert hat, bleibt stehen und
     * wird als Konflikt gemeldet.
     */
    public AiRevertReport revert(VaultId vaultId, UUID changeSetId, String actor) {
        openChangeSet(vaultId, changeSetId);
        if (!changeSets.markReverted(changeSetId, clock.get())) {
            throw new AiWriteRefusedException("Diese KI-Änderung wurde bereits rückgängig gemacht");
        }
        var conflicts = new ArrayList<AiRevertConflict>();
        var reverted = 0;
        for (var change : changeSets.changes(changeSetId).reversed()) {
            var note = notes.findById(vaultId, change.noteId());
            if (note.isEmpty()) {
                conflicts.add(new AiRevertConflict(change.path(), "Die Notiz gibt es nicht mehr"));
                continue;
            }
            var history = snapshots.listSince(change.noteId(), 0);
            if (history.stream().anyMatch(UpdateRecord::ciphertext)) {
                conflicts.add(new AiRevertConflict(note.get().path(), "Die Notiz ist inzwischen verschlüsselt"));
                continue;
            }
            if (!yjs.textOf(payloads(history)).equals(change.textAfter())) {
                conflicts.add(new AiRevertConflict(note.get().path(), "Seit der KI hat jemand weitergeschrieben"));
                continue;
            }
            if (change.kind() == AiChange.Kind.CREATED) {
                remove(note.get(), actor);
            } else {
                writeText(note.get(), change.textBefore(), actor);
            }
            reverted++;
        }
        return new AiRevertReport(reverted, List.copyOf(conflicts));
    }

    public List<AiChangeSet> changeSets(VaultId vaultId, int limit) {
        return changeSets.list(vaultId, limit);
    }

    public Optional<AiChangeSet> changeSet(VaultId vaultId, UUID changeSetId) {
        return changeSets.find(vaultId, changeSetId);
    }

    public List<AiChange> changes(VaultId vaultId, UUID changeSetId) {
        return changeSets.find(vaultId, changeSetId).map(set -> changeSets.changes(set.id())).orElse(List.of());
    }

    /** Ob dieser Dienst Notizen dieses Levels lesen/schreiben darf (dieselbe Schranke wie beim Schreiben). */
    public boolean mayProcess(AiService service, NoteLevel level) {
        try {
            requireLevel(service, level);
            return true;
        } catch (AiWriteRefusedException refused) {
            return false;
        }
    }

    public static boolean isAgent(String actor) {
        return actor != null && actor.startsWith("ki:");
    }

    /** Hängt die Änderung als Yjs-Update an; liefert den Text davor. Parallele Schreiber: neu versuchen. */
    private String writeText(Note note, String text, String actor) {
        for (var attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            var history = plainHistory(note);
            var updates = payloads(history);
            var before = yjs.textOf(updates);
            var update = yjs.change(updates, text);
            if (update.isEmpty()) {
                return before;
            }
            var revision = history.isEmpty() ? 0 : history.getLast().serverSequence();
            var saved = relay.saveIfCurrent(note.id(), revision, update.get());
            if (saved.isPresent()) {
                audit.record(note.vaultId(), note.id(), actor, "note.content-updated", Map.of("revision", saved.get().serverSequence()));
                announcements.announceNoteUpdated(note.vaultId(), note.id(), () -> Optional.of(note.path()));
                return before;
            }
        }
        throw new AiWriteRefusedException("Die Notiz " + note.path() + " ändert sich gerade zu oft - später erneut versuchen");
    }

    private void remove(Note note, String actor) {
        var operationId = "ki-" + UUID.randomUUID();
        notes.delete(note.vaultId(), note.id(), operationId, actor);
        audit.record(note.vaultId(), note.id(), actor, "note.deleted", Map.of("operationId", operationId));
        relay.onNoteDeleted(note.id());
        announcements.announceNoteDeleted(note.vaultId(), note.id(), note.path());
    }

    private List<UpdateRecord> plainHistory(Note note) {
        var history = snapshots.listSince(note.id(), 0);
        if (history.stream().anyMatch(UpdateRecord::ciphertext)) {
            throw new AiWriteRefusedException("Verschlüsselte Notizen kann keine KI lesen: " + note.path());
        }
        return history;
    }

    private static List<byte[]> payloads(List<UpdateRecord> history) {
        return history.stream().map(UpdateRecord::payload).toList();
    }

    private Note note(VaultId vaultId, NoteId noteId) {
        return notes.findById(vaultId, noteId).orElseThrow(() -> new AiWriteRefusedException("Notiz nicht gefunden"));
    }

    private AiChangeSet openChangeSet(VaultId vaultId, UUID changeSetId) {
        var changeSet = changeSets.find(vaultId, changeSetId)
            .orElseThrow(() -> new AiWriteRefusedException("KI-Änderung nicht gefunden"));
        if (changeSet.revertedAt() != null) {
            throw new AiWriteRefusedException("Diese KI-Änderung wurde bereits rückgängig gemacht");
        }
        return changeSet;
    }

    /** Maßgeblich ist die aktuelle Konfiguration, nicht das, was ein Aufrufer über den Dienst behauptet. */
    private AiService configured(String serviceId) {
        return services.find(serviceId)
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + serviceId + " ist nicht (mehr) eingerichtet"));
    }

    /**
     * Zwei Schranken: der Dienst muss das Level verarbeiten dürfen, und das Level-Profil darf
     * KI-Verarbeitung nicht verbieten. Für Levels ohne festgelegtes Profil (2..99, Plan.md
     * Abschnitt 8) entscheidet allein die Dienst-Konfiguration - 100/101 schließt {@link AiService}
     * ohnehin aus.
     */
    private void requireLevel(AiService service, NoteLevel level) {
        if (!service.mayProcess(level)) {
            throw new AiWriteRefusedException(service.name() + " darf Notizen mit Level " + level.value() + " nicht verarbeiten");
        }
        try {
            if (levels.resolve(level).agentProcessing() != AgentProcessing.ALLOWED) {
                throw new AiWriteRefusedException("Level " + level.value() + " schließt KI-Verarbeitung aus");
            }
        } catch (UnresolvedNoteLevelException undefinedProfile) {
            // bewusst erlaubt: der Betrieb hat dieses Level für genau diesen Dienst freigegeben
        }
    }
}
