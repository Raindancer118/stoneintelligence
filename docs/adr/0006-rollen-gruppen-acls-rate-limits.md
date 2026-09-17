# ADR 0006: Rollen/Gruppen, Ordner-/Themen-ACLs, Rate-Limiting (Phase 3, Teil 1)

Status: Angenommen (2026-09-21)

## Kontext

Plan.md Abschnitt 6, Phase 3 ("Identity & Authorization") verlangt frei definierbare
Rollen/Gruppen (nicht das feste Vierer-Set VIEWER/EDITOR/OWNER/ADMIN aus `stonesync`),
Ordner- UND Themen-ACLs (Plan.md Abschnitt 4.4a) sowie Rate-Limits ab dieser Phase. OIDC
(Authentik) ist Teil derselben Phase, aber ohne Zugriff auf eine echte Authentik-Instanz
(Issuer, Client-Registrierung) in dieser Session nicht sinnvoll produktiv verdrahtbar.

## Entscheidungen

1. **Rollen/Gruppen-Domänenmodell** (`identity`-Paket in `platform-api`): `Role` (benanntes
   Berechtigungsbündel aus `Permission` = READ/WRITE/DELETE/CREATE), `Group` (Mitgliederliste),
   Zuordnung ausschließlich über Nutzer→Gruppe→Rolle (keine direkten Nutzer-Rollen-Zuweisungen).
   `AuthorizationRepository.effectivePermissions` bildet die Vereinigung aller Rollen aller
   Gruppen eines Subjects in einem Vault (Allow-Aggregation auf dieser Ebene).
2. **`PathRules`**: 1:1 aus dem in Plan.md Abschnitt 3 als "positiv zu übernehmen" markierten
   `stonesync`-Muster reimplementiert (Code selbst nicht übernommen, da Upstream-Repos laut
   Auftrag nicht weiterverwendet werden) - längster Pfad-Präfix gewinnt, bei gleicher
   Präfix-Länge schlägt eine Nutzer-Regel eine Jeder-Regel. Reine Funktion, exhaustiv getestet.
3. **`TopicRules`**: eigenes Regelwerk (Plan.md Abschnitt 4.4a, kein Sonderfall von PathRules).
   **Policy-Entscheidung, die Plan.md nicht spezifiziert und hier getroffen wurde:** trägt eine
   Note mehrere Themen mit widersprüchlichen Effekten, gewinnt die restriktivste Einzelregel
   (eine verbotene Themen-Zuordnung wird nie durch eine erlaubte "weggestimmt"). **Zur
   Bestätigung durch Tom markiert** - falls eine andere Kombinationslogik gewünscht ist
   (z. B. spezifischste Regel statt restriktivste gewinnt), ist das ein lokaler Austausch in
   `TopicRules.resolve`.
4. **Rate-Limiting**: eigener, kleiner Token-Bucket-Algorithmus (`RateLimiter`) statt einer
   Drittbibliothek wie Bucket4j - bewusst, weil eine unbekannte Library-API-Version ohne
   Möglichkeit zur Doku-Prüfung in dieser Session ein Risiko für stillschweigend falsches
   Verhalten gewesen wäre; der Algorithmus selbst ist klein (~40 Zeilen) und exhaustiv getestet.
   Pro Actor (X-Actor-Header, Fallback Remote-Adresse), In-Memory, einzelinstanz-gebunden -
   dieselbe dokumentierte Grenze wie `TicketService`/`SyncRoomRegistry` vor Phase-3.
5. **Audit-Service**: nutzt die bereits in Phase 1 angelegte `platform.audit_events`-Tabelle.
   Synchroner Schreibpfad (kein Event-Bus), damit ein Audit-Eintrag nie verloren geht, während
   die zugehörige fachliche Änderung erfolgreich war. In `NoteController` für
   create/rename/delete verdrahtet; `GET .../notes/{noteId}/audit` macht den Trail einsehbar
   (Anforderungen.md: "Es sollte eine Audit-Trail pro Datei geben").
6. **OIDC bewusst NICHT in dieser Runde verdrahtet.** Echte Authentifizierung setzt Toms
   Authentik-Issuer-URI und Client-Registrierung voraus - ohne die wäre jede JWT-Validierung
   nur gegen einen selbstsignierten Test-Schlüssel geprüft, nicht gegen die echte Instanz.
   **Wichtiger:** Die neue Rollen/Gruppen-/ACL-Logik ist deshalb noch NICHT als echte
   Autorisierungsprüfung in `NoteController` verdrahtet - der `X-Actor`-Header ist weiterhin
   client-seitig frei behauptbar (`SecurityConfig` bleibt `permitAll()`). Eine Berechtigungs-
   prüfung auf Basis eines vom Client selbst benannten Actors wäre Security-Theater, keine
   echte Sicherheitsgrenze. Domänenlogik und Speicher sind fertig und getestet; die scharfe
   Durchsetzung folgt, sobald OIDC echte, unfälschbare Subjects liefert.

## Konsequenzen

- Neue Migrationen `V3__roles_and_groups.sql`, `V4__acl_rules.sql`.
- `AuthorizationRepositoryContractTest` deckt Fake- und Jdbc-Implementierung einheitlich ab
  (Muster wie `NoteRepositoryContractTest`).
- Sobald OIDC steht: `NoteController` (und später `mcp-adapter`) müssen `effectivePermissions`
  und `PathRules`/`TopicRules` tatsächlich als Vorbedingung vor jeder Mutation/jedem Lesezugriff
  auswerten - das ist der explizite nächste Schritt, kein optionales Feature.

Siehe Plan.md Abschnitt 4.4a und Abschnitt 6 (Phase 3).
