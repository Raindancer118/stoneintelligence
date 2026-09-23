package de.raindancer118.stoneintelligence.platform.sync.relay;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;
import org.springframework.stereotype.Component;

/**
 * Verteilt Bestandsaenderungen (Notiz angelegt / geloescht / umbenannt) sofort an ALLE
 * verbundenen Geraete eines Vaults.
 *
 * <p>Warum es das braucht: {@link SyncRoomRegistry} adressiert immer nur die Sessions, die genau
 * diese Notiz gejoint haben. Seit das Plugin nur noch GEOEFFNETE Notizen joint (Architektur-
 * entscheidung 2026-09-18), erreicht eine notenskopierte Nachricht praktisch niemanden mehr -
 * eine auf Geraet A geloeschte Notiz blieb auf Geraet B unbemerkt liegen, eine dort neu angelegte
 * tauchte erst beim naechsten Obsidian-Start auf. Die Verbindung ist ohnehin schon
 * vault-skopiert (das Sync-Ticket ist es seit der Multiplexing-Umstellung), deshalb ist der
 * Vault die natuerliche Adressierungsebene dafuer.
 *
 * <p>ADR 0002 bleibt unberuehrt: der Server sagt nur, DASS eine Notiz existiert/verschwunden ist
 * und unter welchem Pfad - den Dokumentinhalt interpretiert er weiterhin nicht.
 *
 * <p>Wie {@link SyncRoomRegistry} bewusst in-memory und einzelinstanz-gebunden.
 */
@Component
public class VaultAnnouncementService {

    /** Mindestabstand zweier Inhalts-Ankuendigungen je Notiz - jeder Tastendruck ist ein Update. */
    static final long UPDATE_ANNOUNCE_INTERVAL_NANOS = 1_000_000_000L;

    private final Map<VaultId, Set<VaultSubscriber>> subscribers = new ConcurrentHashMap<>();
    private final Map<NoteId, Long> lastUpdateAnnouncement = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier nanoClock;

    public VaultAnnouncementService() {
        this(System::nanoTime);
    }

    VaultAnnouncementService(java.util.function.LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    public void subscribe(VaultId vaultId, VaultSubscriber subscriber) {
        subscribers.computeIfAbsent(vaultId, id -> new CopyOnWriteArraySet<>()).add(subscriber);
    }

    public void unsubscribe(VaultId vaultId, VaultSubscriber subscriber) {
        subscribers.computeIfPresent(vaultId, (id, current) -> {
            current.remove(subscriber);
            return current.isEmpty() ? null : current;
        });
    }

    public void announceNoteCreated(VaultId vaultId, NoteId noteId, String path) {
        announce(vaultId, SyncFrame.TYPE_VAULT_NOTE_CREATED, noteId, path);
    }

    public void announceNoteDeleted(VaultId vaultId, NoteId noteId, String path) {
        lastUpdateAnnouncement.remove(noteId);
        announce(vaultId, SyncFrame.TYPE_VAULT_NOTE_DELETED, noteId, path);
    }

    public void announceNoteRenamed(VaultId vaultId, NoteId noteId, String newPath) {
        announce(vaultId, SyncFrame.TYPE_VAULT_NOTE_RENAMED, noteId, newPath);
    }

    /**
     * Zustellung je Empfaenger einzeln abgesichert - dieselbe Lehre wie bei
     * {@link SyncRoomRegistry#broadcastExcept}: eine bereits tote, aber noch nicht abgeraeumte
     * Verbindung darf die Ankuendigung an alle nachfolgenden, gesunden Geraete nicht abbrechen,
     * und sie wird sofort abgemeldet statt bei jeder weiteren Ankuendigung erneut zu scheitern.
     *
     * <p>Der Pfad wird nur an Empfaenger ausgeliefert, die die Notiz auch lesen duerfen - sonst
     * waere die Ankuendigung selbst ein Informationsleck ueber Existenz und Ablage fremder Notizen.
     */
    /**
     * Inhalt geaendert: Geraete, die die Notiz gerade NICHT offen haben, gleichen sie sofort ab
     * statt erst beim naechsten periodischen Durchlauf. Hoechstens einmal je
     * {@link #UPDATE_ANNOUNCE_INTERVAL_NANOS} und Notiz; der Pfad (DB-Lookup) wird nur dann
     * aufgeloest. Das Plugin wartet nach einer Ankuendigung laenger als dieses Intervall, bevor es
     * abgleicht - so ist auch das letzte, nicht mehr angekuendigte Update eines Schwalls dabei.
     */
    public void announceNoteUpdated(VaultId vaultId, NoteId noteId, java.util.function.Supplier<java.util.Optional<String>> path) {
        var vaultSubscribers = subscribers.getOrDefault(vaultId, Set.of());
        if (vaultSubscribers.stream().noneMatch(subscriber -> subscriber.wantsContentUpdates() && !subscriber.hasJoined(noteId))) {
            return;
        }
        var now = nanoClock.getAsLong();
        var previous = lastUpdateAnnouncement.get(noteId);
        if (previous != null && now - previous < UPDATE_ANNOUNCE_INTERVAL_NANOS) {
            return;
        }
        lastUpdateAnnouncement.put(noteId, now);
        path.get().ifPresent(resolved -> announce(vaultId, SyncFrame.TYPE_VAULT_NOTE_UPDATED, noteId, resolved));
    }

    private void announce(VaultId vaultId, byte messageType, NoteId noteId, String path) {
        for (var subscriber : subscribers.getOrDefault(vaultId, Set.of())) {
            try {
                if (messageType == SyncFrame.TYPE_VAULT_NOTE_UPDATED
                        && (!subscriber.wantsContentUpdates() || subscriber.hasJoined(noteId))) {
                    continue;
                }
                if (!subscriber.mayRead(path)) {
                    continue;
                }
                subscriber.sendVaultEvent(messageType, noteId, path);
            } catch (SyncSessionSendException failedSend) {
                unsubscribe(vaultId, subscriber);
            }
        }
    }

    public int subscriberCount(VaultId vaultId) {
        return subscribers.getOrDefault(vaultId, Set.of()).size();
    }
}
