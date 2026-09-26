package de.tstieh.stoneintelligence.platform.ai;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.domain.link.LinkText;
import de.tstieh.stoneintelligence.domain.notelevel.AgentProcessing;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevel;
import de.tstieh.stoneintelligence.domain.notelevel.NoteLevelPolicyResolver;
import de.tstieh.stoneintelligence.domain.notelevel.UnresolvedNoteLevelException;
import de.tstieh.stoneintelligence.platform.sync.relay.SnapshotStore;
import de.tstieh.stoneintelligence.platform.sync.relay.SyncRelayService;
import de.tstieh.stoneintelligence.platform.sync.relay.UpdateRecord;
import de.tstieh.stoneintelligence.platform.sync.relay.VaultAnnouncementService;
import de.tstieh.stoneintelligence.platform.sync.yjs.YjsBridge;
import de.tstieh.stoneintelligence.platform.vault.FolderRegistry;
import de.tstieh.stoneintelligence.platform.files.BlobTooLargeException;
import de.tstieh.stoneintelligence.platform.files.FilePaths;
import de.tstieh.stoneintelligence.platform.files.FileQuotaExceededException;
import de.tstieh.stoneintelligence.platform.files.FileRefusedException;
import de.tstieh.stoneintelligence.platform.files.FileService;
import de.tstieh.stoneintelligence.platform.vault.Note;
import de.tstieh.stoneintelligence.platform.vault.NoteKind;
import de.tstieh.stoneintelligence.platform.vault.NotePaths;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;

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
    private final FileService files;
    private final NoteLevelPolicyResolver levels = NoteLevelPolicyResolver.withDefaults();

    public AiWriteService(NoteRepository notes, SnapshotStore snapshots, SyncRelayService relay, YjsBridge yjs,
                          VaultAnnouncementService announcements, FolderRegistry folders, AiAuditRecorder audit,
                          AiServiceDirectory services,
                          AiChangeSetRepository changeSets, Supplier<Instant> clock) {
        this(notes, snapshots, relay, yjs, announcements, folders, audit, services, changeSets, clock, null);
    }

    /** @param files legt die gelesenen Originale ab; {@code null} = keine Dateien (Tests ohne Dateispeicher) */
    public AiWriteService(NoteRepository notes, SnapshotStore snapshots, SyncRelayService relay, YjsBridge yjs,
                          VaultAnnouncementService announcements, FolderRegistry folders, AiAuditRecorder audit,
                          AiServiceDirectory services,
                          AiChangeSetRepository changeSets, Supplier<Instant> clock, FileService files) {
        this.files = files;
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

    /**
     * Legt das gelesene Original als synchronisierte Datei ab, damit die Quellnotiz es verlinken
     * kann. Liegt dieselbe Datei (gleicher Inhalt) schon dort, wird sie weiterverwendet; ist der
     * Name von etwas anderem belegt, bekommt sie einen freien ({@code Brief (2).pdf}). Die Datei
     * gehört zum Change-Set - Rückgängig entfernt sie, solange sie niemand ersetzt hat.
     */
    public WrittenNote storeFile(VaultId vaultId, UUID changeSetId, String path, byte[] content, String contentType,
                                 NoteLevel level) {
        var changeSet = openChangeSet(vaultId, changeSetId);
        var service = configured(changeSet.service());
        requireLevel(service, level);
        if (files == null) {
            throw new AiWriteRefusedException("Dieser Server nimmt keine Dateien auf");
        }
        if (!FilePaths.isValid(path)) {
            throw new AiWriteRefusedException("Ungültiger Dateipfad: " + path);
        }
        var sha256 = sha256(content);
        for (var attempt = 1; attempt <= 50; attempt++) {
            var candidate = attempt == 1 ? path : numbered(path, attempt);
            var existing = notes.findByPath(vaultId, candidate);
            if (!existing.isEmpty()) {
                var same = existing.getFirst();
                if (same.kind() == NoteKind.FILE
                    && files.current(same.id()).map(version -> version.sha256().equals(sha256)).orElse(false)) {
                    return new WrittenNote(same.id(), candidate);
                }
                continue;
            }
            Note file;
            try {
                file = files.create(vaultId, candidate, level, service.agent());
            } catch (FileRefusedException refused) {
                throw new AiWriteRefusedException(refused.getMessage());
            }
            try {
                files.upload(vaultId, file.id(), 0, new ByteArrayInputStream(content), contentType, service.agent());
            } catch (RuntimeException failure) {
                remove(file, service.agent());
                throw failure instanceof FileRefusedException || failure instanceof BlobTooLargeException
                    || failure instanceof FileQuotaExceededException
                    ? new AiWriteRefusedException(failure.getMessage()) : failure;
            }
            changeSets.addChange(new AiChange(UUID.randomUUID(), changeSetId, file.id(), candidate, AiChange.Kind.FILE_CREATED,
                "", sha256, clock.get()));
            return new WrittenNote(file.id(), candidate);
        }
        throw new AiWriteRefusedException("Kein freier Name für " + path);
    }

    private static String numbered(String path, int number) {
        var slash = path.lastIndexOf('/');
        var dot = path.lastIndexOf('.');
        return dot > slash + 1
            ? path.substring(0, dot) + " (" + number + ")" + path.substring(dot)
            : path + " (" + number + ")";
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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

    /** Ein vorgeschlagener Link: das Ziel und das Wort, an dem er haengen soll ({@code allowRelated}: sonst unter "Verwandt"). */
    public record LinkRequest(NoteId target, String anchor, boolean allowRelated, LinkRelation relation) {

        public LinkRequest(NoteId target, String anchor, boolean allowRelated) {
            this(target, anchor, allowRelated, null);
        }
    }

    /**
     * Setzt Links in einer Notiz (ADR 0012) - nur als eingefuegtes Markup, berechnet auf dem Text, der
     * im Moment des Schreibens gilt; wer gleichzeitig tippt, verliert nichts. In Notizen von Menschen
     * nur, wenn der Vault das erlaubt (Standard). Liefert die tatsaechlich gesetzten Links.
     */
    public List<LinkText.Insertion> linkNote(VaultId vaultId, UUID changeSetId, NoteId noteId, List<LinkRequest> requests,
                                             LinkingSettings settings) {
        var changeSet = openChangeSet(vaultId, changeSetId);
        var service = configured(changeSet.service());
        var note = note(vaultId, noteId);
        requireLevel(service, note.level());
        if (!isAgent(note.createdBy()) && !settings.linkHumanNotes()) {
            return List.of();
        }
        var names = linkNames(vaultId);
        var alreadyLinked = changeSets.linkedTargets(vaultId, noteId);
        var wanted = new ArrayList<java.util.Map.Entry<String, LinkRequest>>();
        for (var request : requests) {
            var target = note(vaultId, request.target());
            requireLevel(service, target.level());
            // Einmal verlinkt, nie wieder: wer den Link entfernt hat, will ihn nicht zurueck.
            if (!target.id().equals(noteId) && !target.isFile() && !alreadyLinked.contains(target.id())) {
                wanted.add(java.util.Map.entry(linkTargetOf(target.path(), names), request));
            }
        }
        var max = settings.maxLinksPerNote() == null ? Integer.MAX_VALUE : settings.maxLinksPerNote();
        var applied = new ArrayList<LinkText.Insertion>();
        var linkedTargets = new ArrayList<LinkRequest>();
        writeWith(note, current -> {
            applied.clear();
            linkedTargets.clear();
            var text = current;
            for (var entry : wanted) {
                if (applied.size() >= max) {
                    break;
                }
                var insertion = LinkText.insert(text, entry.getKey(), entry.getValue().anchor(), entry.getValue().allowRelated());
                if (insertion.isPresent()) {
                    applied.add(insertion.get());
                    linkedTargets.add(entry.getValue());
                    text = insertion.get().text();
                }
            }
            return text;
        }, service.agent());
        for (var linked : linkedTargets) {
            changeSets.rememberLink(vaultId, noteId, linked.target(), clock.get());
            if (linked.relation() != null) {
                changeSets.rememberRelation(vaultId, noteId, linked.target(), linked.relation(), changeSetId, clock.get());
            }
        }
        if (!applied.isEmpty()) {
            changeSets.addChange(new AiChange(UUID.randomUUID(), changeSetId, noteId, note.path(), AiChange.Kind.LINKED,
                "", "", clock.get(), applied));
        }
        return List.copyOf(applied);
    }

    /** Dateiname ohne .md je Eintrag, klein - um mehrdeutige Namen zu erkennen. */
    private java.util.Map<String, Long> linkNames(VaultId vaultId) {
        var counts = new java.util.HashMap<String, Long>();
        String cursor = null;
        do {
            var page = notes.list(vaultId, cursor, 500, java.util.EnumSet.allOf(de.tstieh.stoneintelligence.platform.vault.NoteKind.class));
            page.notes().forEach(entry -> counts.merge(baseName(entry.path()).toLowerCase(java.util.Locale.ROOT), 1L, Long::sum));
            cursor = page.complete() ? null : page.nextCursor().orElse(null);
        } while (cursor != null);
        return counts;
    }

    /** Wie Obsidian verlinkt: der Dateiname, bei Namensgleichheit der Pfad (jeweils ohne .md). */
    private static String linkTargetOf(String path, java.util.Map<String, Long> names) {
        var name = baseName(path);
        var withoutMd = path.endsWith(".md") ? path.substring(0, path.length() - 3) : path;
        return names.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), 0L) > 1 ? withoutMd : name;
    }

    private static String baseName(String path) {
        var name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    /**
     * Macht ein Change-Set rückgängig, neueste Änderung zuerst. Eine Notiz wird nur angefasst, wenn
     * ihr Text noch genau dem KI-Stand entspricht - was seitdem jemand geändert hat, bleibt stehen und
     * wird als Konflikt gemeldet.
     */
    public AiRevertReport revert(VaultId vaultId, UUID changeSetId, String actor) {
        var changeSet = openChangeSet(vaultId, changeSetId);
        if (!changeSets.markReverted(changeSetId, clock.get())) {
            throw new AiWriteRefusedException("Diese KI-Änderung wurde bereits rückgängig gemacht");
        }
        var conflicts = new ArrayList<AiRevertConflict>();
        var removedFrom = new java.util.TreeSet<String>(java.util.Comparator.comparingInt((String path) -> -path.length())
            .thenComparing(java.util.Comparator.naturalOrder()));
        var reverted = 0;
        for (var change : changeSets.changes(changeSetId).reversed()) {
            var note = notes.findById(vaultId, change.noteId());
            if (note.isEmpty()) {
                conflicts.add(new AiRevertConflict(change.path(), "Die Notiz gibt es nicht mehr"));
                continue;
            }
            if (change.kind() == AiChange.Kind.FILE_CREATED) {
                var unchanged = files != null && files.current(change.noteId())
                    .map(version -> version.sha256().equals(change.textAfter())).orElse(false);
                if (!unchanged) {
                    conflicts.add(new AiRevertConflict(note.get().path(), "Die Datei wurde seitdem ersetzt"));
                    continue;
                }
                remove(note.get(), actor);
                removedFrom.addAll(de.tstieh.stoneintelligence.platform.vault.FolderPaths.parentsOf(note.get().path()));
                reverted++;
                continue;
            }
            var history = snapshots.listSince(change.noteId(), 0);
            if (history.stream().anyMatch(UpdateRecord::ciphertext)) {
                conflicts.add(new AiRevertConflict(note.get().path(), "Die Notiz ist inzwischen verschlüsselt"));
                continue;
            }
            if (change.kind() == AiChange.Kind.LINKED) {
                // Nur das eingefuegte Markup heraus - was seither geschrieben wurde, bleibt.
                var before = writeWith(note.get(), current -> LinkText.remove(current, change.links()), actor);
                if (before.equals(LinkText.remove(before, change.links()))) {
                    conflicts.add(new AiRevertConflict(note.get().path(), "Die Links wurden inzwischen geändert oder entfernt"));
                } else {
                    reverted++;
                }
                continue;
            }
            if (!yjs.textOf(payloads(history)).equals(change.textAfter())) {
                conflicts.add(new AiRevertConflict(note.get().path(), "Seit der KI hat jemand weitergeschrieben"));
                continue;
            }
            if (change.kind() == AiChange.Kind.CREATED) {
                remove(note.get(), actor);
                removedFrom.addAll(de.tstieh.stoneintelligence.platform.vault.FolderPaths.parentsOf(note.get().path()));
            } else {
                writeText(note.get(), change.textBefore(), actor);
            }
            reverted++;
        }
        // Von der KI angelegte Ordner, die jetzt leer sind, gehen mit - tiefste zuerst.
        for (var folder : removedFrom) {
            folders.deleteIfEmptyAndCreatedBy(vaultId, folder, changeSet.agent(), notes.hasEntriesUnder(vaultId, folder));
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
        return writeWith(note, current -> text, actor);
    }

    /**
     * Wie {@link #writeText}, aber der neue Text wird bei jedem Versuch aus dem dann aktuellen Text
     * berechnet - so ueberschreibt ein Wiederholungsversuch nie, was jemand dazwischen geschrieben hat.
     */
    private String writeWith(Note note, java.util.function.UnaryOperator<String> change, String actor) {
        for (var attempt = 0; attempt < WRITE_ATTEMPTS; attempt++) {
            var history = plainHistory(note);
            var updates = payloads(history);
            var before = yjs.textOf(updates);
            var text = change.apply(before);
            var update = yjs.change(updates, text);
            if (update.isEmpty()) {
                return before;
            }
            var revision = history.isEmpty() ? 0 : history.getLast().serverSequence();
            var saved = relay.saveIfCurrent(note.id(), revision, update.get());
            if (saved.isPresent()) {
                audit.record(note.vaultId(), note.id(), actor, "note.content-updated", Map.of("revision", saved.get().serverSequence()));
                notes.markEdited(note.vaultId(), note.id(), actor, clock.get());
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
        announcements.announceNoteDeleted(note.vaultId(), note.id(), note.path(), note.kind());
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
