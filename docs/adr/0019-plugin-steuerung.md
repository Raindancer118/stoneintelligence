# ADR 0019: Plugins im Unternehmen steuern

Status: Angenommen und umgesetzt (2026-11-25), Prüfung gegen Obsidian 1.15.0

## Kontext

Auftrag (Tom): „Wir brauchen eine Möglichkeit, Obsidian so zu kontrollieren, dass nicht jeder alle Plugins
herunterladen kann.“ Ein Unternehmen will, dass im Firmen-Vault nur freigegebene Community-Plugins laufen, und
will Geräte aussperren oder leeren können. Obsidian selbst kennt Plugins, die jede Person frei installiert; die
Entscheidung „eingeschränkter Modus“ trifft jede Person selbst.

Ehrliche Grundlage vorab: Wer lokale Administratorrechte hat, hebelt jede lokale Maßnahme aus. Das Plugin
allein setzt Leitplanken; ein Schloss entsteht erst, wo Betriebssystem und Identitätsanbieter mitspielen.

## Schichten

| Schicht | Was | Wirkung | Wo |
|---|---|---|---|
| A | Bedingter Zugriff beim Identitätsanbieter („konformes Gerät“) | Der Server gibt nur verwalteten Geräten Tokens | `docs/enterprise/geraete-und-plugins.md` (nur Doku) |
| B | Bereitstellungswerkzeug mit Rechten und Obsidians eigener Richtlinie | Nur festgelegte Plugin-Dateien, nicht durch die Person änderbar | `ops/managed-obsidian/` |
| C | Richtlinie je Vault und Geräteregister auf dem Server | Zentrale Einstellung, Sicht auf jedes Gerät, Sperren, Löschen | `platform-api` (V29), Webapp, Plugin |
| D | Durchsetzung im Plugin | Deaktiviert und blockiert, meldet; Server verweigert Synchronisation | Plugin, `TicketController`, Relay |
| E | Proxy-Regeln | Obsidian erreicht die Plugin-Quellen nicht | `docs/enterprise/geraete-und-plugins.md` |

B und A sind das harte Schloss, D und C sind die Leitplanke samt Überblick.

## Prüfung gegen Obsidian 1.15.0

Gefragt war, ob schreibgeschützte Dateien (`.obsidian/plugins/`, `community-plugins.json`) Obsidian zuverlässig
am Ändern hindern. Das Ergebnis ist nicht eindeutig und hat den Entwurf von B verändert.

**Aufbau.** Echtes Obsidian aus `/opt/Obsidian` (Anwendung 1.15.0, Electron 43.8.4, Chrome 150), Linux. Ein
wegwerfbares Profil pro Lauf: `--user-data-dir` und `HOME`/`XDG_CONFIG_HOME` in ein `mktemp`-Verzeichnis, darin ein
Test-Vault. `~/.config/obsidian` und die echten Vaults wurden nicht berührt; alles wurde danach gelöscht.
Playwrights `_electron.launch` scheitert („Process failed to launch!“), weil Obsidian den Node-Inspector nicht
freigibt. Stattdessen startet Obsidian mit `--remote-debugging-port`, Playwright hängt sich per
`chromium.connectOverCDP` an, und JavaScript läuft im Renderer (`page.evaluate`). Echte Installation über das
Netz: `app.plugins.installPlugin("liamcain/obsidian-calendar-plugin", "1.5.10", {id, name})` lud die Dateien von
`github.com/.../releases/download/...`. Die Befehle liegen als Wegwerf-Skripte nur in der Arbeitskopie und sind
nicht Teil des Repos.

**1. Wo steht der eingeschränkte Modus?** Je Vault in Obsidians Profil, nicht im Vault: `localStorage`-Schlüssel
`enable-plugin-<vaultId>` (`"true"`/`"false"`) in `<Profil>/Local Storage/leveldb`. Ein neuer Vault hat den
Schlüssel nicht (`app.plugins.isEnabled()` ist `false`); `app.plugins.setEnable(true)` setzt ihn. Er überlebt den
Neustart (geprüft nach geordnetem Beenden; ein hartes Beenden schreibt ihn nicht). Die Person ändert ihn jederzeit.
Eine Datei im Vault kann ihn nicht festlegen.

**2. Nur-lesen-Dateien mit `chmod` (derselbe Benutzer, entspricht root-Besitz).** Drei Varianten, je Ablauf
installieren, aktivieren, deaktivieren, deinstallieren, Neustart:

| Aktion | `plugins/` 555 und `community-plugins.json` 444 (auch rekursiv) | nur `community-plugins.json` 444 |
|---|---|---|
| `installPlugin` | scheitert: `EACCES: permission denied, mkdir …/plugins/obsidian-kanban`, Hinweis „Failed to install plugin“, Ergebnis `false`, nichts bleibt zurück | gelingt (Ordner ist schreibbar) |
| `uninstallPlugin` | scheitert: `EACCES … rmdir`, Hinweis „Failed to uninstall plugin“, Dateien und Manifest bleiben | gelingt |
| `disablePluginAndSave` | entlädt das Plugin in der Sitzung, **ohne Fehler und ohne Hinweis**; die Datei bleibt unverändert (Modus 444, gleiche Inode), der Speicher weicht ab | wie links |
| `enablePluginAndSave` (schon installiertes Plugin) | gelingt in der Sitzung, die Datei bleibt unverändert | wie links |
| Neustart | lädt wieder genau die Plugins aus der Datei | wie links |

Obsidian ändert weder Besitz noch Modus und schreibt die Datei nicht anders (keine Ersatzdatei, keine
`chmod`-Aufrufe). Fazit: Ein schreibgeschützter `plugins/`-Ordner verhindert Installieren und Löschen sauber und
laut; ein schreibgeschütztes `community-plugins.json` verhindert nur, dass Änderungen den Neustart überleben.
Beides allein reicht nicht, siehe 3 und 4.

**3. Umgehung: „Konfigurationsordner überschreiben“.** Die Einstellung steht ebenfalls im Profil
(`localStorage["<vaultId>-config"]`). Mit `.obsidian-mine` statt `.obsidian` wird `app.vault.configDir` zu
`.obsidian-mine`; dort (im beschreibbaren Vault) ließ sich Kanban installieren und aktivieren, obwohl `.obsidian/`
gesperrt war. Dateirechte im Vault können das nicht verhindern.

**4. Echte zweite Person (User-Namespace, ohne root).** `unshare --map-auto --map-root-user` ergibt „root“ und eine
zweite, unprivilegierte UID. Mit einem root-eigenen `plugins/`-Ordner, aber `.obsidian/` im Besitz der Person kann
sie `plugins/` wegbenennen und `community-plugins.json` löschen und neu anlegen (beides erlaubt): Das Verzeichnis
entscheidet über Umbenennen und Löschen. Mit **root als Besitzer von `.obsidian/` und Sticky-Bit (Modus 1777)**
ist beides verboten, während die Person weiter eigene Dateien in `.obsidian/` anlegt. Obsidian lief dann als
unprivilegierte UID ohne Auffälligkeiten: `app.json`, `workspace.json` entstanden, ein Plugin speicherte seine
`data.json` (Besitz der Person), die Installation eines anderen Plugins schlug fehl, eine Deaktivierung hielt nur in
der Sitzung. Genau diese Rechte setzt `managed-obsidian.sh`, `ops/managed-obsidian/test/userns.test.sh` prüft sie.

**5. Obsidian hat eine eigene Verwaltungsrichtlinie (neu in 1.15).** Beim Lesen von `app.plugins.installPlugin`
fiel eine Prüfung „Plugin … is not permitted by your organization's policy“ auf. Sie stammt aus einer Datei, die
der Hauptprozess beim Start liest: Linux `/etc/obsidian/policy.json`, macOS `/Library/Application Support/Obsidian/policy.json`,
Windows `C:\Program Files\Obsidian\policy.json` (Ausgabe „Obsidian Work“: `/etc/obsidian-work/…`,
`…/ObsidianWork/…`; Pfade aus dem Programmcode, nur der Linux-Pfad ausgeführt). Felder (`schemaVersion` 1):
`plugins` (`true`, `false` oder Liste erlaubter Kennungen), `corePlugins`, `communityPlugins` (`id`, `version` fest
oder wie `>=1.2.0 <2.0.0`, `blockedVersions`, `enable`: `manual`, `default`, `forced`), `privatePlugins` (mit
`url`), `themes`, `snippets`, `devTools`, `insider`, `minAppVersion`. Ungültige Datei: „locked fallback“, alles zu.
Die Richtlinie gilt nur, wenn Obsidian als `obsidian-work` läuft oder die Profildatei `obsidian.json` `isWork: true`
enthält. Mit der normalen Ausgabe liegt dieser Schalter in einer Datei, die die Person selbst ändern darf, die
Richtlinie ist dort also kein Schloss. Getestet mit `isWork: true` und einer Richtlinie in einem Namespace-Overlay
auf `/etc`: Das festgelegte Plugin in der festgelegten Version ließ sich installieren, eine andere Version, ein
anderes Plugin und ein von Hand in `plugins/` abgelegtes Plugin wurden mit der eingebauten Meldung abgewiesen
(`enablePluginAndSave` lieferte `false`, nichts geladen); der Trick aus 3 half nicht, weil die Richtlinie
prozessweit gilt. **Offen:** wie „Obsidian Work“ bezogen und lizenziert wird; macOS/Windows-Pfade ungeprüft;
`enable: forced`, Versionsbereiche und `privatePlugins` nicht ausgeführt.

### Folgerungen

1. Dateirechte sind nur mit root-eigenem `.obsidian/` samt Sticky-Bit eine echte Sperre, und auch dann umgehbar
   durch „Konfigurationsordner überschreiben“. B liefert sie als zweite Linie, nicht als alleinige.
2. Die einzige Sperre, die das Profil der Person nicht aushebelt, ist Obsidians Richtlinie in der Work-Ausgabe.
   B schreibt sie mit; ohne diese Ausgabe bleibt sie wirkungslos. Das steht so in der README und hier.
3. Gegen jemanden, der den Konfigurationsordner umlenkt und selbst ein verändertes StoneIntelligence-Plugin
   installiert, hilft nur A (der Server gibt dem Gerät keine Tokens) oder ein Gerät ohne Administratorrechte, auf dem
   Obsidian die Richtlinie liest.
4. D ist eine Leitplanke gegen Versehen und unbedachte Installation, nicht gegen Absicht.

## Entscheidungen

### C: Richtlinie je Vault und Geräteregister (V29)

- `platform.plugin_policies` (Vault, `mode` `OFF`/`ENFORCE`, `allowed` als JSON-Liste mit `id` und optionaler fester
  `version`). `stoneintelligence` ist immer erlaubt (ohne das Plugin gäbe es keine Meldung).
- `platform.devices`: je Plugin-Instanz Vault, Geräte-Kennung (zufällig, in den Plugin-Daten), Person, Name,
  Plattform, Obsidian- und Plugin-Version, eingeschränkter Modus, aktivierte Community-Plugins, erste und letzte
  Meldung, Sperre, Löschauftrag, Löschbestätigung. `sync_tickets.device_id` trägt die Kennung bis in die
  WebSocket-Verbindung.
- Endpunkte: `GET`/`PUT /api/v1/vaults/{id}/plugin-policy` (Lesen: Mitglied, Ändern: Verwalten),
  `PUT /devices/{deviceId}` (Meldung des eigenen Geräts, Mitglied), `GET /devices`, `POST /devices/{id}/revoke`,
  `/wipe`, `/reinstate` (Verwalten).
- Bewertung als reine Funktion (`DeviceState.of`): `COMPLIANT`, `NON_COMPLIANT` (mit den verletzenden Plugins),
  `REVOKED`, `WIPE_PENDING`, `UNKNOWN` (nie gemeldet, nur bei `ENFORCE`). Sperre und Löschauftrag gelten auch bei
  `OFF`: Sie sind eine ausdrückliche Entscheidung der Verwaltung. Die Liste bewertet gegen die aktuelle Richtlinie,
  nicht gegen die beim Melden.
- Eine neue Meldung von einer anderen Person auf demselben Gerät übernimmt die Person und behält die Sperre.
- Audit: `plugin-policy.changed`, `device.non-compliant` (nur bei Änderung), `device.revoked`,
  `device.wipe-requested`, `device.wiped`, `device.reinstated`.

### D: Durchsetzung

- **Server.** `POST /sync-tickets` verlangt den Kopf `X-Device-Id` und fragt `DeviceGate` (`DeviceService`).
  Verweigerung: 403 mit `code` (`DEVICE_UNKNOWN`, `DEVICE_REVOKED`, `DEVICE_WIPE`, `DEVICE_NOT_COMPLIANT`) und deutscher
  Meldung, bei der verletzenden Plugins genannt werden. Der Relay prüft beim Verbinden erneut (Ticket gilt Sekunden)
  und schließt offene Verbindungen eines Geräts sofort bei Sperre, Löschauftrag oder neuem Verstoß
  (`DeviceRefused`, Schließcode 4403). Die Prüfung beim Ticket genügt, weil jede Notiz über genau diese eine
  Verbindung joint.
- **Plugin.** Vor jedem Verbindungsaufbau meldet es das Gerät und setzt die Richtlinie durch
  (`prepareConnection`): nicht erlaubte Plugins per `app.plugins.disablePluginAndSave` deaktivieren, dann erneut
  melden; bleibt etwas verletzend, gibt es kein Ticket und die Statusleiste zeigt „Gesperrt“ mit dem Grund. Zusätzlich
  ersetzt es `app.plugins.installPlugin` und `enablePluginAndSave` durch Wächter, die mit deutscher Meldung ablehnen,
  und setzt die zuletzt gelesene Richtlinie auch ohne Netz beim Start durch (jede Minute, gemeldet alle zehn).
  Alle Zugriffe auf `app.plugins` prüfen, ob das Feld existiert; fehlt es (andere Obsidian-Version), tut der Teil
  nichts (Zustand `unavailable`, Eintrag im Debug-Protokoll). Dann bleibt die Prüfung des Servers.
- **Löschen.** Der Auftrag kommt in der Antwort auf die Meldung. Das Plugin löscht alle zugeordneten Notizen und
  Dateien endgültig (nicht in den Papierkorb, ohne dem Server etwas zu melden), leert den Sync-Stand, meldet ab und
  bestätigt mit `wiped` bei der nächsten Meldung. Das ist der Mechanismus „Zugriff entzogen, lokale Kopie
  verschwindet“, aber ohne die Rückfrage bei ungesicherten Änderungen, denn hier gilt der Wille der Verwaltung.
- Die Meldung ist eine Selbstauskunft des Plugins. Ein verändertes Plugin kann falsche Angaben machen. Das ist die
  Grenze von C und D; dagegen stehen A und B.

### B: Bereitstellungswerkzeug

`ops/managed-obsidian/managed-obsidian.sh` (Linux, macOS) und `.ps1` (Windows). Ein Lauf legt Vault und
`.obsidian/plugins` an, installiert genau die Plugins der Richtliniendatei (Release-Dateien von GitHub, Prüfsumme
je Datei, bei Abweichung bricht der Lauf ab und lässt den Bestand unverändert), schreibt `community-plugins.json`,
belegt Server, Anmeldedienst und Client im StoneIntelligence-Plugin vor (nie Tokens), entfernt nicht gelistete
Plugin-Ordner, schreibt Obsidians eigene Richtlinie und setzt die Rechte aus 4. Wiederholung ändert nichts und lädt
nichts (Selbstheilung als Timer). Optional fragt es mit einem Token-Datei-Konto die Richtlinie des Servers ab und
installiert nur, was dort erlaubt ist; ohne Antwort bleibt der Bestand, Rechte werden trotzdem repariert.
Linux/macOS: `.obsidian` root, Modus 1777; `plugins/` und `community-plugins.json` root, 755/644; `data.json` der
Person; auf macOS optional `chflags uchg`. Windows: Besitzer Administratoren, Benutzer nur lesen und ausführen,
`data.json` änderbar, auf `.obsidian` das Recht „Unterelemente löschen“ für Benutzer verweigert. Das Windows-Skript
ist hier nicht ausgeführt und, ohne `pwsh`, nicht einmal geparst worden; der CI-Lauf parst es.

### A und E

Nur Dokumentation (`docs/enterprise/geraete-und-plugins.md`): Entra ID und Okta, Proxy-Regeln für
`raw.githubusercontent.com/obsidianmd/obsidian-releases/` und GitHub-Release-Downloads.

## Folgen und Grenzen

- Wer lokale Administratorrechte hat, ändert Dateien, Richtlinie und Plugin. Wer kein Gerät verwaltet, bekommt
  über D keinen Schutz außer gegen Versehen.
- Ein Gerät, das nie verbindet oder auf dem das Plugin fehlt, erhält keinen Löschauftrag. „Löschen“ ist kein
  Fernlöschen, sondern ein Auftrag beim nächsten Verbinden.
- Eine Sperre schließt offene Verbindungen nur auf der Instanz, die sie bearbeitet; Verbindungen auf einer anderen
  Instanz enden spätestens mit dem nächsten Verbindungsaufbau, denn dann fehlt das Ticket.
- Nicht geprüft: Windows-Skript und Windows-Rechte, macOS (`chflags`, Rechte), Obsidian Work (Bezug), Mobilgeräte
  (iOS/Android kennen `app.plugins` mit demselben Aufbau, aber ohne Dateirechte-Schicht B).
- Weitere Ideen: Abgleich mit dem Obsidian-Community-Katalog (Prüfsummen automatisch), signierte Richtlinien,
  Meldung von Themes und Snippets.
