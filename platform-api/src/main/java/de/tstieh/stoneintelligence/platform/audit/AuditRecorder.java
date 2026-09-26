package de.tstieh.stoneintelligence.platform.audit;

import java.util.Map;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Schreibweg des Audit-Trails - als Port, damit Controller ohne Datenbank testbar sind. */
public interface AuditRecorder {

    /** {@code noteId == null} fuer Ereignisse, die keinen Eintrag betreffen (Ordner, Mitglieder, Rollen). */
    void record(VaultId vaultId, NoteId noteId, String actor, String action, Map<String, Object> payload);
}
