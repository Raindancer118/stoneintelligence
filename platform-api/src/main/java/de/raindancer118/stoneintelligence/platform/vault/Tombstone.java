package de.raindancer118.stoneintelligence.platform.vault;

import java.time.Instant;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/**
 * Ein dauerhafter, expliziter Loesch-Zustand (Plan.md Abschnitt 3, Fehlerklasse 3) - kein
 * ablaufendes Zeitfenster. {@code serverSequence} ist eine serverseitige, monoton wachsende
 * Sequenznummer je Vault; {@code operationId} macht wiederholte Loeschversuche desselben
 * Clients idempotent.
 */
public record Tombstone(UUID id, VaultId vaultId, NoteId noteId, String operationId,
                         long serverSequence, String deletedBy, Instant deletedAt) {
}
