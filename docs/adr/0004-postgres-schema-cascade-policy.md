# ADR 0004: Gemeinsames Postgres, getrennte Schemas, differenzierte Cascade-Delete-Policy

Status: Angenommen (2026-09-11)

## Kontext

`stonesync` ergänzte Cascade-Deletes erst nachträglich über mehrere Migrationen, nachdem
Force-Deletes an FK-Constraints scheiterten oder in Races liefen - und wandte sie teils auch
auf Audit-/Ledger-Daten an, die eigentlich aufbewahrungspflichtig sind (Plan.md Abschnitt 3,
Fehlerklasse 4).

## Entscheidungen

1. **Ein Postgres, getrennte Schemas**: `platform-api` nutzt Schema `platform`,
   `intelligence-worker` Schema `worker`. Geringerer Betriebsaufwand als zwei DBs; ein Split
   bleibt später ohne Anwendungsumbau möglich (Plan.md Abschnitt 8.1).
2. **Cascade-Policy pro Fremdschlüssel einzeln begründet**, nicht pauschal:
   - `notes.vault_id → vaults.id`: `ON DELETE CASCADE` - Vault-Löschung ist eine explizite,
     harte Batch-Operation.
   - `note_snapshots.note_id → notes.id`: `ON DELETE CASCADE` - rohe/konsolidierte
     CRDT-Snapshots sind technisch abhängig, fachlich wertlos ohne die Note.
   - `note_tombstones.vault_id → vaults.id`: `ON DELETE CASCADE`, aber **kein** FK auf
     `note_id` (die Zeile existiert nach der Löschung nicht mehr - der Tombstone ist gerade
     der Beleg dafür). GC läuft über eine explizite Regel, nie über Ablauf einer TTL.
   - `audit_events`: **keine** Fremdschlüssel auf `vaults`/`notes` überhaupt. Audit-Events
     müssen das Löschen des zugehörigen Vaults/Dokuments überleben (Anforderungen.md: "Alle
     Änderungen von Agenten sollen dauerhaft gespeichert werden"). Eine spätere Löschpflicht
     (z. B. DSGVO) wirkt über Payload-Redaktion, nie über Zeilenlöschung.
3. **Job-Queue im `intelligence-worker` ist eine persistierte Outbox-Tabelle**
   (`worker.jobs`), kein In-Memory-Executor - das ist die Zuverlässigkeits-/Backpressure-Grenze
   aus Plan.md Abschnitt 3 (Bulk-Operationen).

## Konsequenzen

- `PlatformSchemaMigrationIT` (Testcontainers) verifiziert die Cascade-Policy tatsächlich
  gegen einen echten Postgres, nicht nur gegen die Migration-Syntax.
- Jede neue Tabelle mit Fremdschlüssel auf `vaults`/`notes` muss ihre Cascade-Entscheidung im
  Migrationskommentar begründen (Review-Kriterium).

Siehe Plan.md Abschnitt 3 (Fehlerklasse 3 + 4) und Abschnitt 8.1.
