# ADR 0011: Alles aus Obsidian steuern, Rechte je Notiz und Ordner

Status: Angenommen (2026-09-26)

## Kontext

Tom möchte alles, was StoneIntelligence kann, auch aus Obsidian heraus steuern, zum Beispiel über
ein Kontextmenü, das Leute zu genau dieser Notiz oder diesem Ordner hinzufügt oder entfernt.
Entscheidungen von Tom (26.09.2026):

- „Jemanden hinzufügen" heißt **echte Rechte je Eintrag** (Lesen/Schreiben/Anlegen/Löschen/
  Verwalten), nicht nur Ein- oder Ausblenden.
- Hinzufügen lassen sich **nur Vault-Mitglieder**. Wer neu ist, wird erst in den Vault eingeladen.
- Obsidian und Webapp bleiben **gleichwertig**: jede Funktion ist in beiden erreichbar, über
  dieselbe API.

Stand heute (Inventur 26.09.2026):

| Bereich | Webapp | Plugin |
|---|---|---|
| Vaults anlegen/auflisten | ja | ja |
| Personen suchen, hinzufügen, per Mail einladen, Einladungen zurückziehen | ja | ja (`InviteModal`) |
| Rollen anlegen, Gruppen anlegen, Mitglieder/Rollen zuordnen | ja | **nein** |
| Pfadregeln (Ordner ein-/ausblenden) | ja | **nein** |
| Protokoll je Notiz (`/notes/{id}/audit`) | ja | **nein** |
| KI: Dokument hochladen, Jobs, Kontingent, Abbrechen | ja | **nein** |
| KI-Änderungen ansehen und rückgängig machen | ja | ja |
| Sync-Status, Pause, erneut synchronisieren | – | ja |

Lücken in der API selbst: keine Mitgliederliste (wer ist im Vault, über welche Gruppe), kein
Löschen/Ändern von Rollen, Gruppen und Pfadregeln, kein Umbenennen des Vaults. Pfadregeln kennen
nur `ALLOW`/`DENY` für eine Person oder alle, keine Gruppen und keine einzelnen Rechte.

## Entscheidungen

### 1. Freigaben je Ordner und je Notiz ersetzen die Pfadregeln

Neue Tabelle `platform.access_grants`: Vault, Ziel (**Ordnerpfad** oder **NoteId**), Wer
(`USER` | `GROUP` | `EVERYONE`), Rechte (Menge aus `READ`, `WRITE`, `CREATE`, `DELETE`, `MANAGE`
oder `NULL` = „wie im Vault"), wer, wann.

Auflösung als reine Funktion `AccessResolver` (wie `PathRules`, exhaustiv getestet):

1. Die **spezifischste** passende Freigabe gewinnt: Notiz vor tiefstem Ordner vor Vault-Rolle.
2. Auf derselben Stufe gilt Person vor Gruppe vor „Jeder". Passen mehrere Gruppen einer Person,
   werden deren Rechte vereinigt.
3. Die gewinnende Freigabe **ersetzt** die Vault-Rechte für diesen Eintrag. Dadurch kann sie
   Rechte **geben** („Anna darf nur diese Notiz bearbeiten") und **nehmen** („Ben sieht diesen
   Ordner nicht" = leere Menge).
4. Ohne passende Freigabe gelten die Vault-Rechte aus Gruppen und Rollen, wie bisher.

Das Ziel einer Notiz-Freigabe ist die NoteId: sie übersteht das Umbenennen und Verschieben.
Ordner-Freigaben wandern beim Umbenennen des Ordners mit (`FolderRegistry.rename` schreibt die
Pfade in derselben Transaktion um).

**Nur Mitglieder:** Person- und Gruppen-Freigaben werden abgelehnt, wenn die Person kein
Vault-Mitglied ist bzw. die Gruppe nicht zum Vault gehört (400).

**Nicht aussperren (von Tom bestätigt):** Wer im Vault `MANAGE` hat, behält auf jedem
Eintrag `MANAGE`. So kann man sich selbst den Lesezugriff nehmen und später wiederherstellen,
aber niemand kann einen Eintrag verwaisen lassen. `MANAGE` auf einem Ordner oder einer Notiz
erlaubt, deren Freigaben zu ändern, und zwar nur bis zu den eigenen Rechten dort (keine
Rechteausweitung).

**Migration V14:** `path_rules` werden zu Ordner-Freigaben (`DENY` → leere Menge,
`ALLOW` → `NULL` = wie im Vault). Die `/path-rules`-Endpunkte bleiben ein Release lang als
Kompatibilitätsschicht auf den Freigaben bestehen (ältere Plugins), dann fallen sie weg.
`TopicRules` bleibt außen vor, bis Notizen Themen haben (eigene Phase).

### 2. Durchsetzung an einer Stelle

`VaultAccessGuard` fragt nur noch `AccessResolver` (Vault-Rechte + Freigaben). Jede heutige
Aufrufstelle (Notizen, Inhalte, Dateien, Ordner, Sync-WebSocket, KI-Schreibweg, Protokoll) geht
darüber. Die KI handelt weiter höchstens mit den Rechten von `requestedBy`, jetzt je Eintrag.

Sync: Ändern sich Freigaben, sendet der Server eine neue Vault-Ankündigung
`VAULT_ACCESS_CHANGED` (neuer Frame-Typ, Opt-in wie Frame 11/13). Daraufhin:

- **Leserecht weg:** Das Plugin behandelt den Eintrag wie „anderswo gelöscht". Das heißt: gibt es
  lokal nie übertragene Änderungen, erscheint die bestehende Löschentscheidung und bietet an, sie
  als lokale Kopie außerhalb des Syncs zu behalten. Es geht nichts stillschweigend verloren.
- **Schreibrecht weg:** Die Notiz wird im Editor schreibgeschützt (CM6 `EditorState.readOnly` +
  Hinweisleiste). Schreibt man sie trotzdem außerhalb des Editors, landet die Fassung als
  Konfliktkopie, wie bei Dateien.
- Der Server lehnt Yjs-Updates ohne `WRITE` auf dem Eintrag schon heute ab. Das wird mit Tests
  für Notiz-Freigaben abgesichert.

### 3. API-Ergänzungen

- `GET /vaults/{v}/access?path=…` bzw. `GET /vaults/{v}/notes/{id}/access`: wer hat welche
  Rechte **und woher** (Rolle X über Gruppe Y / Ordner-Freigabe / Notiz-Freigabe), plus die
  eigenen Rechte des Aufrufers dort. Das Plugin baut daraus das Menü und blendet nur aus, was
  man nicht darf.
- `PUT|DELETE` für Freigaben auf Ordner und Notiz.
- `GET /vaults/{v}/members`: Mitglieder mit Gruppen und effektiven Vault-Rechten.
- Mitglied aus dem Vault entfernen, Gruppen/Rollen umbenennen, löschen, Rollenrechte ändern.
- `PATCH /vaults/{v}` (Umbenennen). **Vault löschen gehört nicht in diesen Plan.** Das vernichtet
  persistente Daten und braucht eine eigene Entscheidung (Aufbewahrung, Backup, Bestätigung).
- Protokoll auch für Ordner (`GET /vaults/{v}/folders/audit?path=`), inklusive Freigabe-Änderungen.
- `POST /vaults/{v}/ai/jobs` auch mit `fileId` einer Datei, die schon im Vault liegt. Das Plugin
  muss eine PDF dann nicht erneut hochladen.

### 4. Obsidian-Oberfläche

- **Kontextmenü** (Dateibaum `file-menu`, Mehrfachauswahl `files-menu`, Editor `editor-menu`),
  je nach eigenen Rechten:
  - **Freigabe…**: Dialog mit Liste „Wer hat Zugriff" samt Herkunft; Mitglied oder Gruppe
    hinzufügen (Voreinstellungen *Lesen*, *Bearbeiten*, *Voll*, *Individuell*); entfernen;
    „auf geerbt zurücksetzen". Bei Mehrfachauswahl gilt das für alle Einträge.
  - **Verlauf und Protokoll**: wer hat geöffnet, bearbeitet, angelegt, umbenannt, freigegeben.
  - **Mit KI einlesen** (PDFs, Bilder, Dokumente): Dienst und Level wählen, Job starten.
  - **KI-Änderungen an dieser Notiz**; **Im Web öffnen** (kb.tstieh.de); **Erneut
    synchronisieren** (gibt es schon).
- **Kennzeichen im Dateibaum:** kleines Symbol für „eingeschränkt", „geteilt" und
  „schreibgeschützt".
- **Ansicht „Vault-Verwaltung"** (eigene `ItemView`, auch auf dem Handy nutzbar), Reiter
  *Mitglieder*, *Gruppen*, *Rollen*, *Einladungen*, *KI* (Jobs mit Fortschritt, Kontingent,
  Abbrechen, Hochladen), *Protokoll*. Sie deckt dasselbe ab wie die Webapp.
- **Befehle** für alles in der Befehlspalette (z. B. „Freigabe der aktuellen Notiz",
  „Vault-Verwaltung öffnen", „KI: Dokument einlesen").
- Reine Logik (`accessPlan.ts`: Herkunft darstellen, Voreinstellungen ↔ Rechte, was das Menü
  zeigt) ist getrennt von der Obsidian-UI und mit Vitest getestet.

### 5. Webapp zieht mit

Freigabe-Dialog je Notiz und Ordner in `NotesWorkspace`, Mitgliederliste, Löschen/Umbenennen von
Rollen und Gruppen. Der Pfadregel-Editor geht im Freigabe-Dialog auf.

### 6. Parität bleibt erhalten

Ein Test listet alle öffentlichen `/api/v1`-Endpunkte (aus den Controller-Mappings) und prüft,
dass jeder sowohl im Plugin-Client (`NoteApiClient.ts`) als auch in `webapp/src/lib/api.ts`
genutzt wird. Ausnahmen stehen begründet in einer Liste (z. B. Sync-Tickets nur im Plugin). Eine
neue Funktion ohne Obsidian-Oberfläche fällt so in CI auf.

## Umsetzung in Schritten (je Schritt test-first, E2E mit zwei Obsidian-Instanzen)

| Version | Inhalt |
|---|---|
| 0.24.0 | Server: `access_grants` + V14-Migration, `AccessResolver`, Guard umgestellt, Access-/Freigabe-/Mitglieder-Endpunkte, Löschen/Ändern von Rollen/Gruppen, `VAULT_ACCESS_CHANGED`. Pfadregel-Kompatibilität bleibt, alte Plugins laufen weiter. |
| 0.25.0 | Plugin: Kontextmenü „Freigabe…", Kennzeichen im Dateibaum, Schreibschutz, Umgang mit entzogenem Leserecht. Webapp: Freigabe-Dialog. |
| 0.26.0 | Plugin: Vault-Verwaltung (Mitglieder, Gruppen, Rollen, Einladungen), Verlauf und Protokoll. Webapp: Mitgliederliste, Löschen/Umbenennen. |
| 0.27.0 | Plugin: KI aus Obsidian (Einlesen vorhandener Dateien, Jobs, Kontingent, Abbrechen). Paritätstest in CI, Pfadregel-Endpunkte entfernt. |

E2E-Prüfungen vor jedem Release: Notiz für B nur lesbar → B kann nicht tippen, A sieht keine
Änderung von B. Leserecht entziehen → Notiz verschwindet bei B, ungesicherte Änderungen führen
zum Dialog. Ordner umbenennen → Freigaben bleiben. Freigabe an Nicht-Mitglied → abgelehnt. Die
KI schreibt nicht in einen Ordner, den `requestedBy` nicht beschreiben darf.

## Nicht Teil dieses Plans

Vault löschen, Themen-ACLs und Note-Levels 2–99/E2EE, Versionsverlauf mit Wiederherstellen. Der
Versionsverlauf bekommt später einen Platz im Menü „Verlauf und Protokoll". Die nächtliche
Verlinkung steht in ADR 0012. Ihre Schalter kommen in den Reiter *KI* der Vault-Verwaltung.
