package de.tstieh.stoneintelligence.domain.id;

import java.util.UUID;

/**
 * Gemeinsamer Supertyp aller stabilen Domänen-IDs. Mutationen adressieren immer eine
 * {@code EntityId}, nie einen veränderlichen Pfad (vgl. Plan.md Abschnitt 3, Fehlerklasse 5).
 */
public sealed interface EntityId permits VaultId, NoteId, DocumentId {

    UUID value();
}
