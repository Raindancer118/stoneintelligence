package de.raindancer118.stoneintelligence.platform.sync.relay;

import de.raindancer118.stoneintelligence.domain.id.NoteId;

/**
 * Eine Verbindung aus Sicht der vault-weiten Bestandsankuendigungen ({@link
 * VaultAnnouncementService}). Bewusst getrennt von {@link SyncSession}: dort geht es um den
 * Inhalt EINER gejointen Notiz, hier um Ereignisse, die den gesamten Vault betreffen und gerade
 * auch Geraete erreichen muessen, die die betroffene Notiz NICHT geoeffnet haben.
 *
 * <p>Die Berechtigungspruefung liegt beim Abonnenten selbst ({@link #mayRead}), weil nur die
 * konkrete Verbindung ihren Actor kennt - der Announcement-Service bleibt dadurch frei von
 * Security-Infrastruktur und testbar.
 */
public interface VaultSubscriber {

    String id();

    /** Darf der Actor dieser Verbindung die Notiz unter diesem Pfad ueberhaupt sehen? */
    boolean mayRead(String path);

    void sendVaultEvent(byte messageType, NoteId noteId, String path);
}
