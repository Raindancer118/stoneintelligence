# ADR 0003: Note-Level als versioniertes Policy-Profil

Status: Angenommen (2026-09-11), Levels 2-99 bewusst offen (s. Konsequenzen)

## Kontext

Anforderungen.md beschreibt Note-Level 1-101 als einfache Zahl mit Sonderbedeutung bei 1, 100
und 101. Eine reine Zahl ist aber keine Policy, sondern ein UI-Kürzel über einer Policy - eine
Code-Verzweigung à la `if level > 99` wäre an jeder Stelle wiederholt und nicht erweiterbar.

## Entscheidung

Ein `NoteLevel` (Zahl 1-101, `domain-core`) wird über einen `NoteLevelPolicyResolver` in ein
`NotePolicyProfile` aufgelöst:

```
classification    (PUBLIC | INTERNAL | CONFIDENTIAL | SECRET)
sync              (ALLOWED | DENIED | E2EE)
serverReadable    (boolean)
agentProcessing   (ALLOWED | DENIED)
sharing           (RESTRICTED | OPEN)
```

Invarianten sind im Konstruktor von `NotePolicyProfile` erzwungen (nicht nur Konvention):
`serverReadable` muss `false` sein, wenn `sync` `E2EE` oder `DENIED` ist; `agentProcessing`
kann nicht `ALLOWED` sein, wenn der Server den Inhalt nicht lesen kann.

Nur die in Anforderungen.md konkret spezifizierten Level sind vorregistriert:

- **Level 1**: `classification=PUBLIC, sync=ALLOWED, serverReadable=true,
  agentProcessing=ALLOWED, sharing=OPEN`
- **Level 100** (keine Synchronisation): `sync=DENIED, serverReadable=false,
  agentProcessing=DENIED, sharing=RESTRICTED`
- **Level 101** (E2EE): `sync=E2EE, serverReadable=false, agentProcessing=DENIED,
  sharing=RESTRICTED, classification=CONFIDENTIAL`

## Konsequenzen

- Level 2..99 sind bewusst **nicht** vorregistriert. `resolve()` wirft
  `UnresolvedNoteLevelException` statt eine Policy zu raten - diese Level werden erst in
  Phase 8 (Note-Level-Feinschliff, Plan.md Abschnitt 6) mit Tom festgelegt.
- `schemaVersion` im Profil erlaubt künftige Schema-Erweiterungen, ohne gespeicherte Profile
  stillschweigend falsch zu interpretieren.
- MCP-/Agent-Zugriff filtert über `agentProcessing`, orthogonal zu `PathRules` (Ordner) und
  Themen-ACLs (Plan.md Abschnitt 4.4a) - keine Vermischung der drei Dimensionen.

Siehe Plan.md Abschnitt 4.1 und `domain-core/.../notelevel/`.
