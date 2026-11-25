# ADR 0015: Aufbewahrung des Protokolls

Status: Angenommen (2026-11-25)

## Kontext

`audit_events` wuchs ohne Grenze und hat bewusst keine Fremdschlüssel (ADR 0004), damit das Protokoll das Löschen von
Notizen und Vaults überlebt. Manche Betriebe brauchen eine Löschfrist (Datensparsamkeit); Anforderungen.md verlangt
zugleich, dass alle Änderungen von Agenten dauerhaft gespeichert bleiben.

## Entscheidung

- Frist je Vault: `vaults.audit_retention_days` (V25), `NULL` = unbegrenzt (Standard), mindestens 365 Tage
  (CHECK-Constraint und `AuditRetention.validate`). Einstellbar mit dem Recht Verwalten über
  `GET`/`PUT /api/v1/vaults/{id}/audit/retention` (`{ "days": 730 }`, `null` = unbegrenzt); Webapp unter
  Einstellungen → Allgemein, Plugin in der Vault-Verwaltung. Angeboten werden 1, 2, 3, 5 und 10 Jahre.
- Täglich um 3:30 Uhr (Europe/Berlin) löscht `AuditRetentionService.purgeExpired` je Vault mit Frist alle Ereignisse
  vor `jetzt − Frist`. **Ausgenommen** (`AuditRetention.isKept`, dieselbe Regel im SQL): Ereignisse von KI-Agenten
  (Akteur beginnt mit `ki:`), Aktionen mit Präfix `ai.` und alles, dessen Nutzdaten ein `changeSet` tragen.
- Beides steht selbst im Protokoll: `audit.retention-changed` (Person, alter und neuer Wert) und `audit.purged`
  (Akteur `system`, Anzahl und Frist) – nur, wenn wirklich etwas gelöscht wurde.

## Grenzen

Gelöschte Ereignisse sind nicht wiederherstellbar; das Setzen einer Frist fragt deshalb vorher nach. Macht ein Mensch
eine KI-Änderung rückgängig, gilt sein Ereignis als gewöhnliches und unterliegt der Frist; der KI-Änderungssatz selbst
bleibt (`ai_change_sets`) unberührt. Mehrere Instanzen dürfen den Job parallel ausführen, das Löschen ist idempotent.
