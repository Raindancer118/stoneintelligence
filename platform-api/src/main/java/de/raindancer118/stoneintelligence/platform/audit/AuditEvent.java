package de.raindancer118.stoneintelligence.platform.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import de.raindancer118.stoneintelligence.domain.id.NoteId;
import de.raindancer118.stoneintelligence.domain.id.VaultId;

/**
 * Ein aufbewahrungspflichtiger Audit-Eintrag (Anforderungen.md: "Es sollte eine Audit-Trail
 * pro Datei geben" / "Alle Änderungen von Agenten sollen geloggt und dauerhaft gespeichert
 * werden"). Getrennt von Betriebslogs (Plan.md Abschnitt 4.4) - dieser Datensatz überlebt auch
 * das Löschen der zugehörigen Note/des Vaults (s. ADR 0004, `audit_events` hat bewusst keine
 * Fremdschlüssel-Constraint).
 */
public record AuditEvent(
    UUID id, VaultId vaultId, NoteId noteId, String actor, String action, Map<String, Object> payload, Instant occurredAt
) {
}
