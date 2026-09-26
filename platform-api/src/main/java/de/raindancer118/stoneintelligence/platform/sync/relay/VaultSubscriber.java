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

    /** Ob diese Verbindung Inhalts-Ankuendigungen ausdruecklich abonniert hat (s. SyncFrame Typ 10). */
    default boolean wantsContentUpdates() {
        return false;
    }

    /** Ob diese Verbindung Ordner-Ankuendigungen ausdruecklich abonniert hat (s. SyncFrame Typ 11). */
    default boolean wantsFolderEvents() {
        return false;
    }

    /** Ob diese Verbindung Datei-Ankuendigungen ausdruecklich abonniert hat (s. SyncFrame Typ 13). */
    default boolean wantsFileEvents() {
        return false;
    }

    /** Ob diese Verbindung die Notiz gerade gejoint hat (und Updates damit ohnehin direkt bekommt). */
    default boolean hasJoined(NoteId noteId) {
        return false;
    }

    void sendVaultEvent(byte messageType, NoteId noteId, String path);

    /**
     * Rechte im Vault haben sich geaendert: gejointe Notizen neu pruefen (Lese-/Schreibrecht kann
     * weg- oder dazugekommen sein) und - falls abonniert - den Client benachrichtigen.
     */
    default void accessChanged() {
    }
}
