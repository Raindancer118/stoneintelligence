# ADR 0013: Ein Vault für einen ganzen Konzern

Status: Angenommen (2026-09-29), Schritte 1–4 umgesetzt (s. Nachtrag)

## Kontext

Tom (29.09.2026): StoneIntelligence soll auch in Firmen wie Google funktionieren, möglichst mit
**einem großen Firmen-Vault** statt vieler kleiner. Zielgröße, gegen die alles gemessen wird:

| Größe | Ziel |
|---|---|
| Mitglieder eines Vaults | 100.000 |
| Einträge (Notizen + Dateien) | 5 Millionen |
| gleichzeitig verbundene Geräte | 50.000 |
| Freigaben | 100.000 |
| API-Instanzen | mehrere hinter einem Load Balancer |

Bisher war alles auf Teams mit einigen hundert bis tausend Notizen ausgelegt. Befunde im Code:

1. **Jedes Gerät lädt alle 30 s die komplette Liste** (`reconcileOnce` → `listAllEntries`, Seiten
   zu 100). Bei 5 Mio. Einträgen: 50.000 Anfragen je Gerät und Durchlauf.
2. **Jedes Gerät hält den ganzen Vault vor.** Bei 5 Mio. Notizen passt das weder auf Laptops noch
   auf Telefone, und Obsidian selbst wird bei solchen Mengen unbenutzbar.
3. **Jede Rechteprüfung lädt alle Freigaben** (`grants.list(vault)`) und prüft sie linear, auch
   für jede einzelne Notiz einer Listen-Seite und bei jedem JOIN.
4. **Mitgliedschaft nur über einzelne Einladungen.** 100.000 Leute einladen geht nicht, und wer
   die Firma verlässt, behält Zugriff, bis ihn jemand austrägt.
5. **Identität ist `preferred_username`.** In Entra ID, Okta oder Google ändert sich der
   Benutzername (Heirat, Umbenennung); dann gehören Notizen, Rechte und Protokoll plötzlich
   „niemandem".
6. **Eine Instanz.** Räume (`SyncRoomRegistry`), Vault-Ankündigungen, Ticket-Einlösung und
   Rate-Limits leben im Speicher eines Prozesses. Zwei Instanzen sehen die Tipper der jeweils
   anderen nicht.
7. **Beitreten spielt die ganze Historie ab.** Eine lange bearbeitete Notiz hat zehntausende
   Updates, die bei jedem JOIN einzeln gesendet werden. Seit dem Versionsverlauf ist das Log
   zugleich die Versionsquelle, darf also nicht einfach gekürzt werden.
8. **Ein GraalJS-Kontext für alle** (`YjsBridge` ist `synchronized`): Web-Speichern, KI-Schreiben
   und Versionen warten aufeinander.
9. **Die Webapp lädt beim Öffnen alle Seiten** der Übersicht (500 je Anfrage).

## Entscheidungen

### 1. Änderungs-Feed statt Voll-Liste

`GET /api/v1/vaults/{v}/changes?since=<cursor>` liefert, was sich seit dem Cursor geändert hat:
Einträge mit Revision, Pfad, Art und Rechten, gelöschte IDs, dazu den nächsten Cursor.

- Cursor ist eine **Postgres-Transaktions-ID** (`xid8`): Jede Zeile in `notes`, `note_snapshots`,
  `note_tombstones`, `folders` und `folder_tombstones` trägt die ID der Transaktion, die sie
  geschrieben hat. Geliefert wird nur, was unter `pg_snapshot_xmin(pg_current_snapshot())` liegt,
  der nächste Cursor ist genau dieser Wert. So entsteht keine Lücke durch Transaktionen, die mit
  kleinerer Nummer später committen (das Problem jeder Sequenz-basierten Lösung). Doppelt
  geliefert werden kann etwas, das ist harmlos, weil jede Aktion idempotent ist.
- Tippen erzeugt keinen Schreibzugriff auf `notes`; der Feed liest neue Snapshots direkt.
- Rechteänderungen verschieben keine Einträge. Deshalb gibt der Feed eine **Zugriffs-Version**
  mit (je Vault, steigt bei jeder Freigabe- und Mitgliedschaftsänderung). Ändert sie sich, holt das
  Gerät einmal die volle Liste seiner Arbeitsbereiche.
- Das Plugin holt die volle Liste nur noch beim ersten Verbinden eines Vaults oder nach einer
  Zugriffsänderung, sonst alle 30 s den Feed (meist leer, eine Anfrage).

### 2. Arbeitsbereiche: nicht jedes Gerät hat alles

Abweichung von Anforderungen.md („Das ganze Vault sollte aktuell gehalten werden"): Das gilt
weiter für normale Vaults. Ab einer Schwelle (Standard 20.000 lesbare Einträge, je Vault
einstellbar) synchronisiert ein Gerät nur seine **Arbeitsbereiche**: gewählte Ordner plus alles,
was man öffnet. Diese Bereiche werden vollständig aktuell gehalten, live wie bisher.

- Server: Liste und Feed nehmen `scope=<ordner>&scope=<ordner>` und filtern in SQL.
- Plugin: Ordnerwahl in den Einstellungen (Baum vom Server, lazy), Befehl „Aus dem Vault holen…"
  (Serversuche, holt die Notiz und hält sie danach aktuell), eigener Bereich jeder Person
  (`Personen/<name>/`) ist immer dabei.
- Bereiche sind Geräte-Einstellungen, nicht Rechte: Wer etwas lesen darf, kann es jederzeit holen.

### 3. Rechte in Mikrosekunden

- Freigaben je Vault liegen **im Speicher als Pfad-Baum** (`GrantIndex`): Auflösen kostet
  O(Tiefe des Pfads) statt O(Anzahl Freigaben).
- Gültig ist der Index, solange die Zugriffs-Version (Entscheidung 1) gleich ist; über mehrere
  Instanzen hinweg meldet `LISTEN/NOTIFY` die neue Version (Entscheidung 6).
- Mitgliedschaften werden je Person und Vault kurz gecacht und bei Änderung verworfen.

### 4. Mitglieder kommen aus dem Identitätsanbieter

- Eine Gruppe in StoneIntelligence kann mit einer **IdP-Gruppe verknüpft** sein (Claim
  `groups`, Name konfigurierbar). Wer beim Anmelden in dieser IdP-Gruppe ist, ist Mitglied, ohne
  Einladung. So wird „alle Beschäftigten lesen, jede Abteilung schreibt ihren Ordner" zu
  einer Handvoll Zuordnungen.
- Die IdP-Gruppen einer Person werden bei jeder Anmeldung gespeichert (für WebSocket und
  nächtliche Läufe, die kein Token haben) und verfallen nach einer Frist ohne Anmeldung.
- **SCIM 2.0** (`/scim/v2/Users`, `/Groups`) für sofortiges Deprovisionieren: deaktiviert der IdP
  jemanden, endet der Zugriff sofort, offene Verbindungen werden geschlossen.
- Der Claim für die Identität ist konfigurierbar (`STONEINTELLIGENCE_OIDC_PRINCIPAL_CLAIM`).
  Für Firmen empfohlen: ein unveränderlicher Claim (`sub` bzw. `oid`), angezeigt wird der Name.
  Die gehostete Instanz bleibt bei `preferred_username`, bis eine Migration der Subjects bereitsteht.

### 5. Beitreten mit einem Zustand statt der ganzen Historie

Der Server legt je Notiz einen zusammengeführten Yjs-Zustand ab (`note_states`: Zustand bis
Revision n, `Y.mergeUpdates` in der Bridge), sobald seit dem letzten mehr als 200 Updates
dazukamen. JOIN sendet diesen Zustand plus die Updates danach. Das Log bleibt vollständig und ist
weiter die Quelle für Versionen.

### 6. Mehrere Instanzen

- Updates, Awareness, Löschungen und Ankündigungen gehen über **Postgres `LISTEN/NOTIFY`** an alle
  Instanzen (Nutzlast: Notiz-ID und Revision; große Updates liest der Empfänger aus der DB).
  Kein neuer Dienst nötig; Redis bleibt eine Option, wenn NOTIFY nicht reicht.
- Tickets liegen schon in Postgres. Rate-Limits bleiben je Instanz (Grenze × Instanzen),
  dokumentiert.
- `YjsBridge` wird ein Pool aus mehreren Kontexten (Größe = Kerne, einstellbar).

### 7. Webapp

Der Ordnerbaum lädt Ebene für Ebene (`GET …/folders/children?path=`), die Liste zeigt nur den
gewählten Ordner, die Suche läuft über den Server. Beim Öffnen wird nichts mehr vollständig geladen.

### 8. Nachweis

Ein Lasttest (`*ScaleIT`, Testcontainers, läuft nicht bei jedem Build) erzeugt einen Vault mit
200.000 Einträgen und 20.000 Freigaben und prüft Obergrenzen: Feed ohne Änderungen < 50 ms,
Liste eines Bereichs mit 1.000 Einträgen < 200 ms, Rechteprüfung < 50 µs.

## Umsetzung in Schritten

| Schritt | Inhalt |
|---|---|
| 1 | `GrantIndex` + Zugriffs-Version + Caches (3) |
| 2 | Änderungs-Feed, Plugin nutzt ihn (1) |
| 3 | Arbeitsbereiche: Server-Filter, Plugin-Auswahl, „Aus dem Vault holen" (2) |
| 4 | Webapp lazy (7) |
| 5 | IdP-Gruppen, Principal-Claim, SCIM (4) |
| 6 | `note_states` für JOIN, Bridge-Pool (5, 6) |
| 7 | LISTEN/NOTIFY zwischen Instanzen (6) |
| 8 | Lasttest (8) |

## Nicht Teil dieses ADR

Mandantenfähigkeit mehrerer Firmen auf einer Instanz über Vaults hinaus, Datenresidenz, E2EE
(Level 101) und Aufbewahrungsregeln (Legal Hold) folgen eigenen ADRs.

## Nachtrag: Schritte 1–4 (29.09.2026)

- **Schritt 1 (V19):** `GrantIndex` + Cache je Vault im `VaultAccessGuard`, gültig bis sich
  `vaults.access_version` ändert (Trigger auf Freigaben, Gruppen, Rollen, Mitgliedschaften und
  Pfadänderungen von Notizen mit Eintrags-Freigabe). Andere Instanzen sehen Änderungen nach
  höchstens 1 s. Ein Zufallstest vergleicht 16.000 Fälle mit der linearen Auflösung.
- **Schritt 2 (V20):** Feed wie beschrieben. `access_events` statt einer Zugriffs-Version für
  Geräte: Eine Person bekommt nur Ereignisse, die sie selbst, eine ihrer Gruppen oder alle
  betreffen, als `relist` (äußerste Ordner). Die Zugriffs-Version bleibt für den Rechte-Cache.
  Die Liste im Plugin ist ein Spiegel im Speicher (`ServerMirror`); der Abgleichsplan bleibt unverändert.
- **Schritt 3 (V21):** Arbeitsbereiche. Abweichend vom Entwurf gibt es keine feste Schwelle:
  Verwaltende schalten „Nur Arbeitsbereiche" je Vault ein (`vaults.selective_sync`), jedes Gerät
  kann es überstimmen. Gefilterte Listen blättern nach Pfad (`COLLATE "C"`, je Bereich ein
  Bereichsscan); die Vollständigkeit während des Ladens sichert der Feed-Cursor, der vorher geholt wird.
  Geräte melden ihre Bereiche über die Sync-Verbindung (Frame 16); Ankündigungen außerhalb
  gehen nicht mehr an sie. Notizen außerhalb, die ein Gerät hat (geholt, selbst angelegt,
  verschoben), werden angeheftet statt ignoriert; gelöscht wird lokal nur nach Rückfrage und nur,
  was unverändert ist.
- **Schritt 4:** Webapp-Baum Ebene für Ebene (`GET …/folders/children`, `GET …/notes?folder=`
  über die berechnete Spalte `notes.parent`).
- **E2E-Befund:** Ein Bereichswechsel darf erst gelten, wenn entschieden ist, was mit Kopien
  außerhalb passiert, und während des Wechsels ruhen die Abgleiche. Sonst hält ein Durchlauf
  die Kopien für „fehlt auf dem Server" und heftet sie an. Geprüft mit echtem Obsidian 1.13.7,
  lokalem `platform-api` und Postgres 18.

Bekannte Grenzen: Rechteänderungen senden weiter Frame 15 an alle Verbindungen des Vaults (die
Geräte fragen dann den Feed, der meist leer ist). Jedes Anheften lädt die Liste der Bereiche einmal
neu. Für Postgres-Transaktions-IDs gilt: Eine lang laufende Transaktion (Backup) verzögert den Feed.
