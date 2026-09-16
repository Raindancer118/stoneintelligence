# ADR 0002: Autoritative Repräsentation einer Note

Status: Angenommen (2026-09-11)

## Kontext

Drei Kandidaten konkurrieren als "Wahrheit" einer Note: die Markdown-Datei im Vault, der
Yjs-CRDT-Zustand, und der in Postgres gespeicherte Zustand. Das muss vor dem Sync-Slice
(Plan.md Phase 2) feststehen, weil es bestimmt, wessen Zustand bei Konflikten gewinnt und wie
Note-Level-Wechsel (insbesondere der Übergang zu Level 101/E2EE) technisch greifen.

## Entscheidung

Der **Yjs-CRDT-Zustand ist Source of Truth** - konsistent mit dem "dummen Server"-Prinzip aus
`stonesync`, bei dem der Server rohe CRDT-Operationen relayt, ohne sie zu interpretieren.

- Die Markdown-Datei im Obsidian-Vault ist eine **Serialisierung** dieses Zustands.
- Postgres speichert **nur Snapshots/History** des CRDT-Zustands (`platform.note_snapshots`),
  nicht den Zustand selbst als Quelle.
- Note-Level-Wechsel (z. B. normal → 101) wirken auf dem CRDT-Zustand, nicht auf der Datei.

## Konsequenzen

- Mutationen adressieren immer die stabile `NoteId`, nie den Pfad (s. `domain-core`,
  Fehlerklasse 5 aus Plan.md Abschnitt 3).
- Für Level 101 (E2EE) muss der Client Kompaktierung/Snapshots vertrauenswürdig erzeugen und
  verschlüsselt abliefern - der Server kann als reiner Blob-Relay nicht mehr mergen.
- JGit bleibt zusätzlich nützlich für lesbaren Vault-Snapshot/Blame, ist aber nicht die
  Quelle der Wahrheit und nicht der Undo-Mechanismus (s. ADR zu KI-Change-Sets, Phase 6).

Siehe Plan.md Abschnitt 4.1 und Abschnitt 8.4.
