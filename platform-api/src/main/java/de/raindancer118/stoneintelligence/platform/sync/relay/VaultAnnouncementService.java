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

    private final Map<VaultId, Set<VaultSubscriber>> subscribers = new ConcurrentHashMap<>();

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
    private void announce(VaultId vaultId, byte messageType, NoteId noteId, String path) {
        for (var subscriber : subscribers.getOrDefault(vaultId, Set.of())) {
            try {
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
