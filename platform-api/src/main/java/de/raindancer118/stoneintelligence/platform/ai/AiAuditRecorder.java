package de.raindancer118.stoneintelligence.platform.ai;

import java.util.Map;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/** Schmale Sicht auf den Audit-Trail - produktiv {@code AuditService::record}. */
@FunctionalInterface
public interface AiAuditRecorder {

    void record(VaultId vaultId, NoteId noteId, String actor, String action, Map<String, Object> payload);
}
