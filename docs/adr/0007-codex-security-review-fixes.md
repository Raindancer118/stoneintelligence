# ADR 0007: Unabhängiger Codex-Review — gefundene und behobene Bugs

Status: Angenommen (2026-09-21)

## Kontext

Auf Wunsch von Tom wurde das Repo von einem unabhängigen Reviewer (OpenAI Codex CLI, via
`codex exec`, nicht Teil der eigentlichen Implementierungs-Session) daraufhin geprüft, ob die
in `Project.md`/den ADRs als "umgesetzt" dokumentierten Features tatsächlich funktionieren. Der
Review baute das Projekt, führte alle Tests aus und las den Code der Kernfeatures gegen
Anforderungen.md und Plan.md. Ergebnis: Build und Tests waren grün, aber mehrere konkrete Bugs
und Sicherheitslücken wurden gefunden, die über die bereits bekannte, dokumentierte Lücke
("OIDC/ACL-Durchsetzung fehlt noch") hinausgingen. Alle wurden verifiziert und behoben.

## Gefundene und behobene Probleme

1. **Kritisch — Cross-Vault-IDOR im Ticket-Pfad.** `TicketController` stellte WebSocket-Tickets
   aus, ohne zu prüfen, dass die angegebene `noteId` zum angegebenen `vaultId` gehört. Wer eine
   fremde NoteId kannte/erriet, konnte ihre komplette Yjs-Historie lesen/schreiben — unabhängig
   vom bereits dokumentierten OIDC-Defizit. **Fix:** `TicketController` prüft jetzt
   `NoteRepository.findById(vaultId, noteId)` vor der Ticket-Ausstellung.
2. **Hoch — Operation-ID-Replay über Notes hinweg.** Die Tombstone-Idempotenz war an
   `(vault_id, operation_id)` gebunden statt an `(vault_id, note_id, operation_id)`. Ein
   wiederverwendeter `operationId`-Wert für eine ANDERE Note im selben Vault lieferte denselben
   Tombstone zurück, wodurch der Controller die Sync-Session der falschen Note schloss und einen
   irreführenden Audit-Eintrag schrieb. **Fix:** Unique-Constraint und Lookup umgestellt.
3. **Hoch — Cross-Vault-Rollenzuweisung.** `assignRole` prüfte nicht, ob Gruppe und Rolle
   demselben Vault angehören; `effectivePermissions` filterte nur die Gruppe, nicht die Rolle
   nach Vault. **Fix:** `assignRole` verifiziert jetzt beide Vaults, `effectivePermissions`
   joint zusätzlich `roles.vault_id` (Verteidigung in der Tiefe).
4. **Hoch — nicht-atomare Löschung/Audit.** `delete()` und die Controller-Mutation+Audit-Eintrag
   liefen nicht in einer gemeinsamen Transaktion. **Fix:** `@Transactional` auf
   `JdbcNoteRepository.rename/delete`, `JdbcAuthorizationRepository.assignRole` sowie
   `NoteController.create/rename/delete`.
5. **Hoch — Reconciliation-Epoch nur kosmetisch.** Keyset-Pagination sortierte nach der NoteId
   (UUID) statt nach einer monoton wachsenden Sequenz. Eine UUID hat keine Beziehung zur
   Einfügereihenfolge - eine während der Pagination neu eingefügte Note mit "kleinerer" UUID
   wurde dauerhaft übergangen, obwohl die letzte Seite fälschlich `complete=true` meldete. Das
   unterlief genau die Garantie, die Fehlerklasse 2 (Plan.md Abschnitt 3) herstellen sollte.
   **Fix:** neue `sequence bigserial`-Spalte auf `platform.notes`, Keyset-Pagination und Cursor
   auf diese Sequenz umgestellt statt auf die NoteId.
6. **Mittel — ACL-Konflikte reihenfolgeabhängig.** `PathRules`/`TopicRules` hatten bei gleicher
   Spezifität keinen deterministischen Tie-Breaker; das Ergebnis hing von der (nicht
   garantierten) DB-Rückgabereihenfolge ab. **Fix:** DENY gewinnt jetzt Unentschieden als
   sicherer Default, zusätzlich `ORDER BY id` in den Jdbc-Listing-Queries.
7. **Mittel — Rate-Limiter Speicher-DoS.** Die Bucket-Map wuchs unbegrenzt mit der Anzahl
   beobachteter Schlüssel (`X-Actor` ist clientseitig frei wählbar). **Fix:** beiläufige
   Eviction "idler" (voll aufgefüllter) Buckets ab einem konfigurierbaren Schwellwert.
8. **Hoch (Plugin) — initialer Notiz-Inhalt ging nie zum Server.** `main.ts` befüllte den
   `Y.Text`, bevor `SyncClient` seinen Update-Listener registrierte - das erzeugte Yjs-Update
   verpuffte ungesendet. **Fix:** Reihenfolge umgedreht (Client zuerst verbinden), plus eine
   kurze Gnadenfrist vor dem Einspielen des lokalen Inhalts, damit ein eventueller Late-Joiner-
   Catchup nicht mit dem lokalen Inhalt kollidiert (additiver CRDT-Merge zweier unabhängiger
   Volltexte wäre sonst möglich) - dokumentierte Grenze, da das WS-Protokoll noch kein "Catchup
   abgeschlossen"-Signal kennt.
9. **Hoch (Plugin) — Umbenennen scheiterte immer.** `NoteApiClient.renameNote` sendete keinen
   `X-Actor`-Header, den der Server zwingend verlangt (→ HTTP 400). Der zugehörige Test
   erwartete fälschlich genau das unvollständige Verhalten. **Fix:** Header ergänzt, Test
   korrigiert.
10. **Hoch (Plugin) — kein Reaktion auf Fremd-Löschung.** `SyncClient.onclose` war nie gesetzt;
    Close-Code 4404 (Note von einem anderen Client gelöscht) wurde ignoriert. **Fix:** neuer
    `onNoteDeleted`-Callback, in `main.ts` verdrahtet - stoppt die Sync-Session, verwirft die
    NoteId-Zuordnung, informiert den Nutzer. Bewusst KEINE automatische Löschung der lokalen
    Datei (ein Server-Signal ohne Korrelation zu einer eigenen Operation ist keine ausreichende
    Grundlage für eine irreversible lokale Aktion).

## Nicht behoben (bewusst, mit Begründung)

- **Volles bidirektionales Reconciliation** (Erstellungen/Umbenennungen/Löschungen ANDERER
  Clients für Notes, die nicht gerade aktiv per WebSocket verbunden sind) fehlt weiterhin. Das
  ist eine substanzielle neue Funktion (periodisches Abfragen aller Seiten des
  Reconciliation-Endpunkts, Abgleich gegen den lokalen Vault-Zustand, OperationJournal-
  Korrelation für jede automatisch angewendete Änderung), keine punktuelle Fehlerkorrektur -
  verdient eine eigene, testgetriebene Session statt als Anhängsel eines Bugfix-Durchgangs
  reingedrückt zu werden.
- **OperationJournal bleibt In-Memory** (kein Neustart-Schutz). Das Risikofenster ist in der
  Praxis sehr klein (Obsidian liefert Vault-Events nahezu synchron nach dem Schreiben) und eine
  Persistenz würde ein neues Risiko einführen (ein verwaister Fingerprint nach einem Absturz
  könnte einen späteren, echten Nutzer-Edit fälschlich unterdrücken) - bewusst nicht "gefixt".
- **OIDC/echte ACL-Durchsetzung** bleiben unverändert offen (s. ADR 0006) - unverändert durch
  Toms fehlende Authentik-Zugangsdaten blockiert.

## Konsequenzen

- Migration `V1` erneut geändert (Tombstone-Unique-Constraint, `notes.sequence`-Spalte) - noch
  unkritisch, da nie produktiv migriert.
- `ReconciliationCursor`s öffentliche API hat sich geändert (`lastSeenSequence` statt
  `lastSeenId`) - reiner interner Implementierungsdetail-Wechsel, keine Breaking-Change-Sorge
  nach außen (der Cursor-Token bleibt für Clients weiterhin ein opaker String).
- 110 Java-Unit-Tests (vorher 100), 25 Plugin-Tests (vorher 23), alle grün.
