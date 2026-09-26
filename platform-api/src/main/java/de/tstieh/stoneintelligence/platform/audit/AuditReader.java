package de.tstieh.stoneintelligence.platform.audit;

import java.util.List;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/** Lesezugriff auf den Audit-Trail - als Port, damit Controller ohne Datenbank testbar sind. */
public interface AuditReader {

    /** Alle Ereignisse eines Eintrags, aelteste zuerst. */
    List<AuditEvent> listForNote(VaultId vaultId, NoteId noteId);

    /** Die juengsten {@code limit} Ereignisse des Vaults, neueste zuerst. */
    List<AuditEvent> listRecent(VaultId vaultId, int limit);
}
