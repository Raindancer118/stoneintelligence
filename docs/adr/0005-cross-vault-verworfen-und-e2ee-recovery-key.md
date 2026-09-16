# ADR 0005: Cross-Vault verworfen, E2EE-Recovery-Key optional und clientseitig

Status: Angenommen (2026-09-11)

## Entscheidung 1: Kein Mehr-Vault-/Cross-Vault-Betrieb

Cross-Vault-Links (`stonesync/plugin+server`) werden **nicht** übernommen. Nicht in
Anforderungen.md gefordert, hätte Sichtbarkeit, Backlinks, E2EE und Löschsemantik erheblich
verkompliziert (übereinstimmende Empfehlung aus beiden Reviews, Codex und Gemini 3.1 Pro).

Mandanten-/Vault-Zugehörigkeit wird im Identitäts-/ACL-/Schlüsselmodell dennoch so angelegt,
dass ein Vault sauber isoliert ist (`vault_id` als Grenze überall im Schema, s. ADR 0004) -
ohne das Cross-Vault-Feature aktiv zu bauen.

## Entscheidung 2: E2EE-Schlüsselverwaltung (Level 101)

Optionaler, **clientseitig erzeugter und verschlüsselt exportierter Recovery-Key**, den Nutzer
aktiv anlegen können. Kein Server-Escrow als stiller Default. Ein zusätzlicher
Enterprise-Escrow-Modus bleibt als separat aktivierbarer Modus möglich. Wer maximale
Sicherheit über Wiederherstellbarkeit stellt, lässt den Recovery-Key einfach weg.

Für kollaborative Level-101-Notes braucht es zusätzlich einen symmetrischen Document-Key, der
pro Nutzer/Gerät asymmetrisch verpackt und beim Berechtigungswechsel neu verteilt wird
(Standard-Gruppen-Verschlüsselungsmuster) - Details werden im E2EE-Spike in Phase 2
festgeschrieben, nicht erst in Phase 7.

## Konsequenzen

- Das Identitäts-/ACL-Schema (Phase 3) braucht keine Cross-Vault-Beziehungstabellen.
- Das Schlüsselmodell (Phase 2, E2EE-Spike) muss Schlüsselrotation, Geräteentzug und den
  Übergang normal → 101 als transaktionalen Zustandsübergang abdecken, bevor produktiver
  Rollout beginnt.

Siehe Plan.md Abschnitt 8.2, 8.3 und Abschnitt 4.1.
