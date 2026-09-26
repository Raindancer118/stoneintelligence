package de.tstieh.stoneintelligence.platform.vault;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Eine Seite der Vault-Reconciliation (Plan.md Abschnitt 3, Fehlerklasse 2). {@code epochId}
 * ist ueber alle Seiten EINES Listing-Durchlaufs stabil - ein Client darf eine als
 * {@code complete=false} markierte Antwort NIE als Grundlage fuer Loeschungen verwenden.
 */
public record ReconciliationPage(UUID epochId, boolean complete, Optional<String> nextCursor, List<Note> notes) {
}
