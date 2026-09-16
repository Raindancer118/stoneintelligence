# StoneIntelligence — Implementierungsplan

Stand: 2026-09-11 (Rev. 2, nach Review durch Codex + Gemini 3.1 Pro). Basiert auf
`Anforderungen.md` sowie einer Code-Analyse der beiden Vorgänger-Repos
`Raindancer118/stonesync` (Java/Spring-Boot-Server + Obsidian-TS-Plugin, Yjs-CRDT-Sync) und
`Raindancer118/stoneai` (Java-CLI, PDF/Notiz → atomare Obsidian-Notizen via LLM). Ein dritter,
veralteter Prototyp `stonesync-server` (TypeScript) wurde bereits vom Java-Server abgelöst und
wird nicht weiter betrachtet.

**Rev. 2 — was sich gegenüber der ersten Fassung geändert hat:** Codex und Gemini haben
unabhängig voneinander dieselben Kernprobleme identifiziert: E2EE zu spät im Phasenplan,
JGit-Commits als Undo-Mechanismus zu schwach, Service-Schnitt zu grobkörnig, Cascade-Deletes zu
pauschal (auch auf Audit-Daten angewendet), mehrere Anforderungen nur genannt statt modelliert
(Rollen/Gruppen, Themen-ACLs, Wiedervorlagen). Diese Fassung baut das ein — Änderungen sind mit
**„Rev. 2“** markiert.

**Prämisse laut Auftrag:** Der aktuelle Code-Stand der Upstream-Repos wird **nicht** übernommen
(neue Historie, neues Repo), aber Architektur, Designs und einzelne gut getestete Bausteine
werden wiederverwendet, wo sinnvoll. Mehrere konkrete, in Produktion aufgetretene Fehler aus
`stonesync` fließen als **Design-Vorgaben** ein (Abschnitt 3).

---

## 1. Scope-Einordnung

Die neue Anforderungen.md ist deutlich größer als beide Vorgänger zusammen:

| Bereich | Deckung durch Vorgänger |
|---|---|
| Sync-Service (Kollaboration, Cursor, Permissions, Verlauf) | **stonesync** deckt den Kern bereits ab (MVP, live verifiziert) |
| Intelligence-Service (Ingestion, atomare Notizen, Verknüpfung) | **stoneai** deckt die Pipeline ab, aber CLI-only, kein Server, kein Knowledge Graph, kein bidirektionales semantisches Linking, kein Undo über UI |
| Note-Levels (1–101, E2EE), Tag-gesteuerte Verschlüsselung | **komplett neu**, keine Vorarbeit |
| Agent Services (MCP-Server, SELMA, Agenten-Identität) | **komplett neu**, keine Vorarbeit |
| SSO/OIDC, Rate-Limits, Transportverschlüsselung | teilweise vorhanden (Authentik-OAuth2 im Invite-Flow), aber nicht als vollwertiges SSO für alle Login-Pfade, keine Rate-Limits |

Das Projekt ist damit kein "Weiterbau", sondern ein **Neuentwurf mit Wiederverwendung von
Bausteinen und Lektionen**.

---

## 2. Architekturentscheidung

**Rev. 2:** Der ursprüngliche Schnitt vermischte Quellcode- und Deployment-Grenzen
(`intelligence-service` war gleichzeitig "Modul oder eigener Dienst"). Codex' Kritik greift:
das führt leicht zu einem verteilten Monolithen mit doppelter Auth-/DB-Logik. Neuer Schnitt,
klar in fachliche Module (Java-Packages) und Laufzeitprofile (Deployables) getrennt:

```
StoneIntelligence/
├── platform-api/            Spring Boot (Java 25) — Sync-Kern (Yjs-Relay), Auth/OIDC,
│                            Permissions (Rollen/Gruppen/PathRules/Themen-ACLs), Audit,
│                            Attachments, History, Rate-Limiting, REST + WebSocket.
│                            Fachliche Module darunter: identity, vault, sync, authorization,
│                            audit — jedes mit genau einem DB-Owner.
├── intelligence-worker/     Spring Boot (Java 25), EIGENES Deployable von Anfang an (nicht
│                            "optional Modul") — Ingestion-Pipeline, Knowledge Graph, Embeddings,
│                            KI-Change-Sets. Arbeitet ausschließlich über eine persistierte
│                            Job-Queue (Outbox-Tabelle in Postgres), nie über In-Memory-Executor
│                            als Zuverlässigkeitsgrenze.
├── mcp-adapter/             DÜNNER Protokoll-Adapter für Agenten — keine eigene Auth-, ACL- oder
│                            Such-Logik, ruft ausschließlich die Application-APIs von
│                            platform-api/intelligence-worker auf (SELMA lebt im
│                            intelligence-worker, nicht hier). Verhindert eine vierte
│                            Parallel-Implementierung von Auth/ACL/Upload/Suche.
├── domain-core/             BEWUSST KLEIN (Rev. 2: kein "globaler Shared Kernel"): nur stabile
│                            IDs (Note-ID, Vault-ID, Document-ID) + Note-Level-Policy-Profil
│                            (s. 4.1). Frontmatter-Schema, Graph-Relationstypen etc. leben als
│                            versionierte Contracts (s. contracts/), nicht als Java-Shared-Code,
│                            da Plugin/Webapp ohnehin TypeScript sind.
├── contracts/               NEU (Rev. 2) — OpenAPI + JSON-Schema für REST/MCP-Tools, daraus
│                            generierte Java- und TypeScript-Typen. Verhindert Schema-Drift
│                            zwischen platform-api, intelligence-worker, webapp und plugin.
├── webapp/                  React + Vite — Dashboard, Wiedervorlagen, Upload-UI, Berechtigungs-
│                            verwaltung, Vault-Browser
├── plugin/                  Obsidian-Plugin, TypeScript — Sync-Client, Cursor, Note-Level-UI,
│                            Tag-gesteuerte E2EE-Aktivierung
├── docker-compose.yml       Deployables + Postgres + Objektspeicher
└── .github/workflows/       CI (Build+Test je Modul) und Release (Tags → GitHub Release)
```

`platform-api` und `intelligence-worker` teilen sich eine Datenbank (verschiedene Schemas,
klare Owner je Tabelle), aber niemals eine JVM/einen Prozess — das hält den latenzkritischen
Sync-Pfad frei von KI-Workload-Spitzen und macht Skalierung/Sicherheitsgrenze explizit
(beantwortet die frühere offene Entscheidung 8.1, s. Abschnitt 8).

**Tech-Stack-Entscheidungen** (nach CLAUDE.md-Vorgabe Java bevorzugen, wo sinnvoll):

- Backend: Java 25 + Spring Boot (wie in Anforderungen vorgegeben) — bewährt aus `stonesync`.
- Sync-Protokoll: Yjs-CRDT wird beibehalten (konfliktfrei, live verifiziert) — der Server bleibt
  bewusst "dumm" (opake Blobs, kein Parsen von Yjs-Updates im Java-Code).
- DB: Postgres (bewährt), inkl. `pg_trgm` für Fuzzy-Search, plus **pgvector** neu für Embeddings.
- Versionsverlauf: JGit-basierte Vault-Historie wird übernommen (Konzept + Wiederherstellung).
- Frontend: React + Vite (wie vorgegeben).
- Plugin: TypeScript, esbuild (wie vorgegeben), CodeMirror-6-Bindung für Yjs bleibt Ansatz der Wahl.
- KI-Anbindung: `ai-gateway`-Bibliothek (bereits vorhandenes eigenes Maven-Paket auf
  packages.tstieh.de, genutzt in `stoneai`) wird als Basis für konfigurierbare KI-Endpunkte/Provider
  wiederverwendet und um Embeddings-Aufrufe erweitert.

---

## 3. Konkrete Fehler aus `stonesync` und daraus abgeleitete Design-Vorgaben

Aus der Commit-Historie (u.a. v1.5.2–v1.7.3) und dem Code lassen sich sechs wiederkehrende
Fehlerklassen extrahieren. Diese werden **von Anfang an** im Design berücksichtigt, statt wie im
Original nachträglich gepatcht zu werden:

1. **Feedback-Schleifen zwischen Server-getriebenen und nutzergetriebenen Filesystem-Events.**
   Im Plugin löste `app.vault.trash()` (serverseitig ausgelöste Löschung) denselben
   `vault.on("delete", …)`-Handler aus wie eine echte Nutzerlöschung — das propagierte serverseitig
   angestoßene Löschungen fälschlich zurück zum Server und führte zu einer kaskadierenden
   Datenlöschung über mehrere Clients hinweg innerhalb von Sekunden.
   → **Vorgabe (präzisiert, Rev. 2):** Ein einfaches "systemisch"-Flag reicht nicht — Obsidian
   bündelt Filesystem-Events asynchron und in wechselnder Reihenfolge, ein Flag kann daher am
   falschen Event landen. Stattdessen ein **persistiertes Operationsjournal**: jede
   selbstinitiierte Operation bekommt eine `operationId` + erwarteten Ziel-Fingerprint (Pfad,
   Content-Hash); eingehende Vault-Events werden dagegen korreliert statt gegen ein Zeitfenster
   oder einen einzelnen Flag-Wert. Umbenennungen werden als eine atomare fachliche Operation
   behandelt, selbst wenn das Dateisystem mehrere Rohereignisse dafür meldet.

2. **Reconciliation ohne Plausibilitätsprüfung.** Eine transiente, unvollständige Server-Antwort
   (z. B. während eines Neustarts) sah identisch aus wie "der Nutzer hat fast alles gelöscht" und
   führte zu echtem, kaskadierendem Datenverlust bei mehreren Kollaborateuren gleichzeitig.
   → **Vorgabe (präzisiert, Rev. 2):** Eine reine Prozentschwelle ist nur die letzte
   Sicherheitslinie, nicht die Lösung — Löschungen dürfen grundsätzlich nicht aus der bloßen
   Abwesenheit eines Objekts in einer möglicherweise unvollständigen Serverliste abgeleitet
   werden. Jede Serverantwort trägt eine **Snapshot-/Epoch-ID, ein Vollständigkeits-Flag und
   einen Pagination-Cursor / High-Water-Mark**; eine als unvollständig markierte Antwort löst
   nie eine Löschung aus. Massenlöschungen (über der Plausibilitätsschwelle) landen zusätzlich in
   einer Quarantäne mit expliziter Bestätigung statt automatisch angewendet zu werden.

3. **Zeitbasierte Tombstone-Fenster statt expliziten Zustands.** Ein 60-Sekunden-Fenster für
   "gerade gelöscht" reichte unter Last (Bulk-Delete von hunderten Dateien) nicht aus, weil der
   tatsächliche Round-Trip länger dauerte als das Zeitfenster.
   → **Vorgabe (präzisiert, Rev. 2):** Pending-Deletes/Tombstones sind ein expliziter, dauerhafter
   Zustand (kein ablaufender Timer) mit **serverseitiger Sequenznummer und idempotenter
   Operations-ID** — nicht nur "bis zur Serverantwort", denn ein länger offline gewesenes Gerät
   kann eine Löschung sonst wieder aufleben lassen. Eine definierte GC-Regel (Retention-Frist,
   Geräte-Checkpoint erreicht, oder erzwungener neuer Baseline-Snapshot) räumt Tombstones auf,
   nie ein bloßer Ablauf.

4. **Fehlende Kaskaden-Deletes im Datenbankschema von Anfang an.** Cascade-Deletes für Yjs-Updates,
   Snapshots, Attachments, Restore-Queue, Links, Invites und API-Key-Exchanges wurden erst
   nachträglich über mehrere Migrationen ergänzt, nachdem Force-Deletes an FK-Constraints
   scheiterten oder in Races liefen.
   → **Vorgabe (präzisiert, Rev. 2):** `ON DELETE CASCADE` gilt für technisch abhängige,
   fachlich wertlose Kind-Daten (rohe Yjs-Update-Logs nach einem konsistenten Snapshot, offene
   Invites, API-Key-Exchange-Tokens) — **explizit NICHT** für Audit-Events, Provenienz-/Ledger-
   Einträge, Revisionshistorie oder KI-Change-Sets. Diese sind eigenständig aufbewahrungspflichtig
   (Anforderung "Alle Änderungen von Agenten sollen dauerhaft gespeichert werden") und müssen ein
   Löschen des zugehörigen Dokuments/Vaults **überleben** (ggf. mit redigiertem Payload statt
   Löschung). Jede Fremdschlüssel-Beziehung bekommt ihre Cascade-Policy einzeln begründet, nicht
   pauschal für "alle Kind-Tabellen von documents und vaults". Harte, erlaubte Löschungen bleiben
   ein einzelnes atomares Batch-Statement.

5. **ID-Auflösung über Cache statt autoritativer Quelle.** Löschungen von Dateien, die z. B. über
   einen Bulk-Download oder ein Live-Event ins Vault kamen (und nie einen echten `resolve()`-Call
   ausgelöst hatten), scheiterten lautlos, weil nur ein Cache geprüft wurde.
   → **Vorgabe (präzisiert, Rev. 2):** Alle Mutationen adressieren grundsätzlich die stabile
   Dokument-ID, nie den Pfad — der Pfad ist ein veränderliches Attribut. Jede Operation, die eine
   Server-ID braucht, hat einen garantierten Fallback auf eine autoritative Auflösung; ein Cache
   ist nie die einzige Quelle. Ein serverseitiges `resolve(path)` bleibt bei gleichzeitigen
   Umbenennungen dennoch race-anfällig — deshalb ID-first, Pfad nur als Anzeige-/Suchattribut.

6. **Einmalig eingefangene Konfiguration/Settings statt Live-Zugriff.** Anzeigename/Farbe wurden
   beim Plugin-Start als Strings in langlebige Objekte kopiert; spätere Änderungen in den
   Einstellungen wirkten erst nach Neustart.
   → **Vorgabe (präzisiert, Rev. 2):** Ein Live-Accessor behebt nur den einfachsten Fall.
   Zusätzlich brauchen laufende Sessions **versionierte Konfigurations-Snapshots bzw.
   Änderungsereignisse**, damit Farbe, Berechtigungen, Note-Level und Provider-Wechsel atomar und
   nachvollziehbar wirksam werden — nicht nur "beim nächsten Lesen irgendwann konsistent".

Zusätzlich beobachtet, aber weniger kritisch: Bounded-Thread-Pool-Queues ohne Retry/Backpressure
führten bei Bulk-Operationen zu stillen Teilausfällen (500er nach Queue-Overflow).
→ **Vorgabe (präzisiert, Rev. 2):** Reines Retry-mit-Backoff ist **keine** Backpressure und kann
Überlast sogar verschärfen. Bulk-Endpunkte (Reindex, Bulk-Ingest im `intelligence-worker`)
bekommen **persistierte Jobs** (Outbox-Tabelle) statt In-Memory-Queues, dazu Admission Control,
Quoten, Idempotency-Keys und `429`/`503` mit `Retry-After`; automatische Client-Retries sind
begrenzt und gejittert.

**Positiv zu übernehmende Muster** (bewusst gut designt, keine Fehlerquelle):
- `PathRules`-Präzedenzlogik (längster Pfad-Präfix gewinnt, Nutzer-Regel schlägt Jeder-Regel,
  reine Funktion ohne Spring/JPA-Abhängigkeit, exhaustiv testbar) — Vorlage für die
  **ordnerbasierten** Sichteinschränkungen. **Deckt Themen-/Tag-basierte Einschränkungen NICHT
  ab** (Rev. 2, Kritikpunkt aus dem Review) — dafür braucht es eine eigene, serverseitig
  autoritative Themen-/Tag-Klassifikation samt eigenem Regelwerk, s. 4.4a.
- Content-addressed Attachments (SHA-256, serverseitig verifiziert, Dedup über alle Vaults hinweg).
- Kurzlebige Single-Use-Tickets für WebSocket-Auth (da der Obsidian-WS-Client keine Custom-Header
  senden kann) — Vorlage auch für Note-Level-101-Handling (Server darf Ticket ausstellen, ohne
  je den Inhalt zu sehen).
- `ManagedBlock` + additives Frontmatter-Merging aus `stoneai` — exakt das, was die Anforderung
  "Überschreiben darf nicht zu Verlust bestandskräftiger Inhalte führen" verlangt.
- Vierstufiger Schutzmechanismus (`#NoStoneAI`-Tag, Namensmarker, `.stoneaiignore`, PDF-Metadaten)
  — direkte Vorlage/Erweiterung für Note-Level- und E2EE-Tag-Steuerung.

---

## 4. Neue Bausteine ohne Vorarbeit

### 4.1 Note-Levels & E2EE

**Rev. 2 — grundlegend überarbeitet.** Beide Reviews haben hier die schwerste technische Lücke
gefunden: eine reine Zahl 1–101 ist keine Policy, sondern ein UI-Kürzel über einer Policy, und
E2EE bricht die Yjs-CRDT-Live-Sync-Architektur (der Server relayt heute rohe CRDT-Operationen,
die er verstehen muss, um sie zusammenzuführen — bei Ciphertext-Blobs kann er das nicht mehr).

- **Level wird intern als versioniertes Policy-Profil geführt**, nicht als reine Zahl:
  ```
  classification = confidential
  sync = allowed | denied | e2ee
  server_readable = true | false
  agent_processing = allowed | denied
  sharing = restricted | open
  ```
  Die Zahl 1–101 aus der Anforderung bleibt als **Frontmatter-/UI-Kürzel** erhalten (Nutzer
  denken in Zahlen), wird aber beim Schreiben in eines dieser Profile aufgelöst — nie direkt als
  Code-Verzweigung à la `if level > 99`.
- **E2EE (Level 101) ist keine Phase-7-Ergänzung, sondern eine Design-Entscheidung ab Phase 2**
  (s. Phasenplan): Yjs-Updates werden clientseitig verschlüsselt, bevor sie den Server erreichen;
  der Server speichert/relayt ausschließlich Ciphertext und kann sie nicht mehr zusammenführen —
  Kompaktierung/Snapshots müssen deshalb vom Client vertrauenswürdig erzeugt und ebenfalls
  verschlüsselt abgeliefert werden. Zusätzlich zu klären, bevor Code entsteht: Schlüsselrotation,
  Geräteentzug, AEAD-/Nonce-Format, verschlüsselte Attachment-Keys, Awareness-/Cursor-Daten
  (dürfen nicht im Klartext lecken), Metadaten-Minimierung (Pfad/Größe/Zugriffsmuster sind auch
  bei Level 101 potenziell aussagekräftig), und der Übergang normal → 101 (Klartext-Altstände auf
  Server und in Backups müssen bereinigt werden, nicht nur künftige Updates verschlüsselt sein).
- **Schlüsselmodell für kollaborative Level-101-Notes:** ein reines "Schlüsselpaar pro Gerät ohne
  Recovery" reicht nur für Single-User-Silos. Sobald mehrere Berechtigte an einer Level-101-Note
  arbeiten, braucht es einen **symmetrischen Document-Key**, der pro Nutzer/Gerät asymmetrisch
  verpackt und beim Berechtigungswechsel neu verteilt wird (Standard-Gruppen-Verschlüsselungsmuster).
- **Tag-gesteuerte Aktivierung kollidiert mit "Server sieht nur Ciphertext"**, wenn Tags im
  Klartext-Frontmatter stehen: der Server kann dann anhand des Tags filtern, sieht aber auch den
  Tag im Klartext — das ist für Level ≤99 unproblematisch, für Level 101 muss der Tag-Wechsel als
  **transaktionaler Zustandsübergang** behandelt werden (Re-Verschlüsselung, nicht bloßes Editieren
  eines Frontmatter-Feldes), sonst liegt kurzzeitig Klartext-Klassifikation neben Ciphertext-Body.
- Level 2..99 bleiben server-sichtbar; MCP-/Agent-Zugriff wird pro Level-Profil (`agent_processing`)
  gefiltert — Erweiterung der Permission-Pipeline um eine `AgentVisibility`-Dimension, orthogonal
  zu `PathRules` (Ordner) und den neuen Themen-ACLs (4.4a).
- Level 100 (keine Synchronisation) ist rein clientseitig und unkritisch — analog zum
  bestehenden vierstufigen Schutzmechanismus aus `stoneai`, nur dass hier gar nichts den Client
  verlässt statt nur nicht von der KI gelesen zu werden.

### 4.2 Intelligence-Worker (Erweiterung von `stoneai-core`)
- Übernahme der Pipeline-Struktur (Loader → Chunker → Extraction → Consolidator → VaultWriter,
  Managed Blocks, Ledger, Protection, Frontmatter) als Bibliothek, aber:
  - **Web-Upload statt CLI-Inbox**, Mehrfach-Upload gleichzeitig, Fortschritt in der Webapp, über
    eine persistierte Job-Queue (nicht In-Memory) mit Upload-Autorisierung, Größenlimits/Quoten
    pro Vault/Nutzer.
  - **Wissensmodell VOR Knowledge Graph** (Rev. 2, wichtigste Korrektur an 4.2): schon beim
    ersten Ingest werden **Quellen, extrahierte Claims/Konzepte, Zitate und eine Konfidenz**
    strukturiert erfasst — nicht erst "Notes", die später nachträglich in einen Graphen gepresst
    werden. Grund: Phase 4 (Ingestion) lief im ursprünglichen Plan vor Phase 5 (Graph/Undo-Modell)
    — das hätte spätere Migrationen und Neuverarbeitung des bereits Eingelesenen erzwungen.
  - **Knowledge Graph** als eigene Schicht darüber: typisierte, gerichtete Kanten (`uses`,
    `requires`, `part_of`, `related_to`, `described_by`, …) mit Provenienz, Ersteller, Konfidenz,
    Gültigkeitszeitraum — Knoten sind nicht nur Notes, sondern auch Quellen/Dokumente/Chunks/
    Claims. Rekursive Graph-Traversierung zunächst in Postgres (CTEs); wird das bei realer
    Vault-Größe zum Performance-Problem, ist eine dedizierte Graph-DB ein späteres, isoliertes
    Migrationsprojekt (kein Blocker für v1).
  - **Semantische, bidirektionale Verlinkung als asynchroner Background-Job** (Rev. 2 — im
    Original synchron beim Speichern angenommen, skaliert ab wenigen Tausend Dokumenten nicht):
    Embeddings pro Chunk/Claim (nicht nur pro ganzer Note), Kandidatensuche über pgvector,
    Entscheidung durch ein stärkeres Modell läuft als eigener Queue-Job, nicht im Request-Pfad
    des Speicherns.
  - **Vault-weite Embeddings-Indizierung**, inkrementell bei jeder Note-Änderung über denselben
    asynchronen Job aktualisiert; Embedding-Modell- und Dimensionsversion wird mitgespeichert,
    damit ein Modellwechsel eine gezielte Reindex-Migration statt eines stillen Bruchs auslöst.
  - **KI-Revisionshistorie & Undo — NICHT über JGit-Commit-pro-Änderung** (Rev. 2, zweitwichtigste
    Korrektur): Ein Git-Revert kann eine KI-Änderung nicht sauber isolieren, sobald danach ein
    Mensch oder ein anderer Agent dieselbe Notiz bearbeitet hat — das war im Original-Plan zu
    stark versprochen. Stattdessen: jede KI-Operation erzeugt ein **domänenspezifisches
    Change-Set** (Basisversion/Yjs-State-Vector, betroffene Notes, exakte Operationen,
    Quellreferenz). Ein Undo ist eine **konfliktgeprüfte Kompensationsoperation** gegen den
    aktuellen Zustand, kein Zurückspulen der Datei. JGit bleibt zusätzlich nützlich für einen
    lesbaren Vault-Snapshot/Blame, ersetzt aber nicht das Undo-Modell.
  - **Additive Überarbeitung bestehender Einträge**: `Frontmatter.mergeAdditively` +
    `ManagedBlock`-Konzept aus `stoneai` genügt bereits der Anforderung "Überschreiben darf
    bestehenden Umfang nicht mindern" — wird 1:1 übernommen, nicht neu erfunden.
  - **Vertrauenswürdige externe Quellen als Policy, nicht nur als Schalter** (Rev. 2, bisher
    fehlend): Domain-Allow-/Denylist, Zitationspflicht für jede nicht aus dem Upload stammende
    Aussage, Prüfung auf unabhängige Zweitquelle statt Einzelquelle (die Anforderung nennt
    Wikipedia explizit als unzureichend) — der reine An/Aus-Schalter (Default: aus) bleibt
    bestehen, bekommt aber diese Policy-Ebene darunter.
  - **Konfigurierbar:** Ob KI von Menschen erstellte Dateien bearbeiten darf; KI-Endpunkte/
    Provider je Vault/Mandant konfigurierbar inkl. Secret-Verwaltung über den vorhandenen
    Vault-Mechanismus (nicht Klartext in Config).

### 4.3 Agent Services (`mcp-adapter` + SELMA)
- `mcp-adapter` ist ein **dünner Protokoll-Adapter** (Rev. 2: keine eigene Auth-/ACL-/Such-Logik
  mehr) — er ruft ausschließlich die Application-APIs von `platform-api` (Auth, Permissions) und
  `intelligence-worker` (SELMA) auf. Verhindert eine vierte Parallel-Implementierung von
  Auth/ACL/Upload/Suche neben Webapp, Plugin und Sync-Server.
- **SELMA** (Semantic Embeddings & Linked Memory Agent Search Engine) lebt im `intelligence-worker`
  als Hybrid-Suche aus Volltext (`pg_trgm`), Vektor (pgvector) und Graph-Traversierung mit
  Reranking. **Kritischste Regel (Rev. 2, aus beiden Reviews):** ACL-/Note-Level-Filterung muss
  **innerhalb der Kandidatensuche** greifen, nicht als Nachfilter auf die ANN-Treffer — ein
  nachträgliches Herausfiltern liefert nicht nur schlechte Trefferzahlen, sondern kann über
  Scores, Trefferanzahl oder Timing Informationen über verborgene Notes leaken (Seitenkanal).
  Mandant/Vault/Level/ACL sind daher Teil der Indexpartitionierung bzw. der Suchstrategie selbst.
- Rollout read-only zuerst: SELMA liefert zunächst nur lesende Suche an Agenten; mutierende
  MCP-Tools (Notes anlegen, Dateien hochladen) folgen mit engeren Scopes, Quoten und optionalem
  Approval-Schritt — kein "Agent darf von Tag 1 alles, was ein Mensch darf".
- "Das KI-Modell soll das Vault als Knowledge Graph tatsächlich verstehen" wird als **prüfbares
  Qualitätskriterium** operationalisiert statt als Prosa-Ziel (Rev. 2): Recall@K auf einem
  festen Fragenkatalog, korrekte Quellenzitate, keine Treffer aus unberechtigten Ordnern/Themen,
  Graph-Traversal-Genauigkeit — als Teil der Abnahmekriterien für Phase 5/7.
- Agenten erhalten eine eigene Identität (Name, ggf. Avatar/Farbe wie menschliche Kollaborateure),
  jede Aktion wird im Audit-Trail mit dieser Identität und im Frontmatter (`created_by`/
  `modified_by: agent:<name>`) geführt.
- Agenten dürfen über MCP Notes anlegen und Dateien hochladen — läuft über dieselbe Ingestion-
  Pipeline wie Web-Uploads (kein Sonderpfad, keine Logikduplikation).

### 4.4a Rollen/Gruppen & Themen-ACLs (Rev. 2, bisher nur nominell erwähnt)
- **Frei definierbare Rollen und Gruppen** sind ein eigenes Domänenmodell im `platform-api`
  (nicht nur die vier festen Rollen VIEWER/EDITOR/OWNER/ADMIN aus `stonesync`): Gruppen als
  Mitgliederlisten, Rollen als benannte Berechtigungsbündel, Nutzer→Gruppe→Rolle-Zuordnung,
  mit klarer Vererbungs- und Deny-vs-Allow-Semantik.
- **Themen-/Tag-ACLs** sind kein Sonderfall von `PathRules` (die nur Ordnerpfade kennen), sondern
  ein eigenes Regelwerk: eine serverseitig autoritative Themen-Klassifikation pro Note (aus
  Frontmatter-Tags oder Knowledge-Graph-Zuordnung), gegen die eine Sichteinschränkungsregel
  greift — Präzedenzlogik analog zu `PathRules` (spezifischste Regel gewinnt), aber eigene Tabelle.
- **Dashboard/Wiedervorlagen** braucht ein eigenes Domänenmodell (bisher komplett unmodelliert):
  Wiedervorlage = Note-Referenz + Fälligkeitszeitpunkt + Ersteller + Empfänger, API im
  `platform-api`, Anzeige in der Webapp.
- **"Zuletzt geöffnet/bearbeitet/erstellt" sauber getrennt** (bisher nur "Audit" vage genannt):
  eigene Felder `created_by`/`last_edited_by`/`last_opened_by` inkl. Zeitstempel; "Öffnen"-Events
  sind datenschutzrelevant (Tracking von Lesezugriffen) und bekommen eine eigene, kürzere
  Aufbewahrungsfrist als Bearbeitungs-Audit.
- **Sync-Queue braucht Cursor/Checkpoints**, nicht nur "einsehbar": persistierter Sync-Cursor pro
  Gerät, Fehlerzustände, Retry/Cancel pro Queue-Eintrag, definiertes Verhalten nach langer
  Geräteabwesenheit (Server-Retention der Änderungen ist endlich).

### 4.4 Allgemein
- SSO/OIDC als **einziger** Auth-Pfad für Menschen (Ausbau des bestehenden Authentik-OAuth2-Ansatzes
  von einem Zusatzpfad zum Standardweg für Webapp, Plugin-Login und Dashboard); API-Keys bleiben
  für Agenten/Geräte.
- Rate-Limits serverseitig (z. B. Bucket4j o. ä., pro API-Key/Nutzer) — **ab Phase 3**, nicht erst
  in der Härtungsphase (Rev. 2: Rate-Limits und AuthZ sind keine Nachbesserung).
- TLS für REST und WebSocket verpflichtend (Reverse-Proxy-Terminierung dokumentiert wie bisher).
- Server-Logs: konfigurierbare Aufbewahrungsdauer/Detailgrad, Rotation, bewusst klein gehalten
  (strukturiertes, komprimiertes Logging). **Getrennt von Audit-Logs** (Rev. 2): Betriebslogs sind
  frei löschbar/rotierbar, Audit-Events sind aufbewahrungspflichtig und unveränderlich (s. 3.4) —
  zwei verschiedene Systeme mit unterschiedlichem Zweck, nicht eine gemeinsame Log-Pipeline.

---

## 5. Wiederverwendungs-Matrix (kurz)

| Baustein | Quelle | Übernahmeform |
|---|---|---|
| Yjs-Sync-Kern, Ticket-Auth, WS-Handler | stonesync/server | Portieren + Fehlerklassen aus Abschnitt 3 beheben, E2EE-Verschlüsselungsschicht davor (Rev. 2) |
| PathRules (Ordner-ACLs) | stonesync/server | Direkt übernehmen, um `AgentVisibility` erweitern — deckt Themen-ACLs NICHT ab (s. 4.4a) |
| Attachments (Content-Hash, Dedup) | stonesync/server | Direkt übernehmen |
| JGit-Vaultverlauf, Restore | stonesync/server | Übernehmen als Snapshot/Blame-Werkzeug — NICHT als Undo-Mechanismus (s. 4.2, Rev. 2) |
| Cross-Vault-Links | stonesync/plugin+server | **Verworfen** (Rev. 2, Empfehlung beider Reviews: nicht gefordert, verkompliziert Sichtbarkeit/E2EE/Löschung erheblich — s. 8.2) |
| Ingestion-Pipeline, ManagedBlock, Ledger, Protection | stoneai-core | Als Bibliothek übernehmen, um Quellen-/Claim-Modell, Knowledge Graph, Embeddings erweitern |
| CLI (`stoneai-cli`) | stoneai | Nicht übernehmen — Upload/Verwaltung läuft über Webapp/MCP, kein separates CLI-Produkt gefordert |
| stonesync-server (TS-Prototyp) | stonesync-server | Verworfen, bereits historisch abgelöst |

---

## 6. Phasenplan (Rev. 2 — neu geordnet nach Review-Feedback: Sicherheits-/Datenmodell-
Entscheidungen mit Tragweite stehen vorne, Härtung ist keine reine Schlussphase mehr)

1. **Architektur- & Sicherheitsentscheidungen (ADRs), Fundament:** Vault/Mandanten-Modell,
   Dokumentidentität (autoritative Repräsentation: Datei vs. Yjs-Dokument vs. DB-Zustand — muss
   vor Phase 2 feststehen), `domain-core` (IDs, Note-Level-Policy-Profil), `contracts/`
   (OpenAPI/JSON-Schema), Postgres-Schema inkl. differenzierter Cascade-Delete-Policy (s. 3.4),
   Auth-Grundgerüst (OIDC), CI/CD-Skelett.
2. **Vertikaler Sync-Slice inkl. E2EE-Grundentscheidung:** Server + Plugin-MVP analog `stonesync`,
   mit den 6 Design-Vorgaben aus Abschnitt 3 bereits eingebaut (Operationsjournal, Epoch-basierte
   Reconciliation, persistente Tombstones, ID-first-Adressierung). **E2EE-Spike parallel**
   (Rev. 2): eine Level-101-Note inkl. Attachment, zweitem Gerät, Geräteentzug, Snapshot und
   Wiederherstellung — Schlüsselformat/-modell wird hier festgeschrieben, nicht erst in Phase 7.
3. **Identity & Authorization:** OIDC, Geräte, frei definierbare Rollen/Gruppen, Ordner- UND
   Themen-ACLs (4.4a), Rate-Limits, Audit-Grundgerüst (getrennt von Betriebslogs) — bewusst vor
   der Intelligence-Pipeline, da SELMA und MCP später darauf aufsetzen.
4. **Verlauf & Dashboard:** JGit-Historie/Restore, lokale Sync-Queue mit Cursor/Checkpoints,
   Wiedervorlagen-Domänenmodell + Webapp-Dashboard.
5. **Wissens-/Provenienzmodell + Ingestion v1:** Quellen, Claims, Konzepte, Change-Sets (s. 4.2)
   werden VOR dem produktiven Ingestion-Rollout festgelegt; danach Upload → zitierte Claims →
   atomare Notes, additive Überarbeitung, Schutzmechanismen.
6. **Knowledge Graph & Embeddings:** typisierte Relationen, pgvector-Index (chunk-/claim-weise),
   asynchrone bidirektionale semantische Verlinkung, KI-Change-Set-Undo (nicht JGit-basiert).
7. **SELMA & MCP-Adapter:** zuerst read-only Suche mit ACL-in-der-Kandidatensuche, dann
   mutierende Tools mit engeren Scopes/Quoten/Approval; Agenten-Identität, Audit-Integration.
8. **Note-Level-Feinschliff & Skalierung/Betriebsreife:** verbleibende Level-Profile (2–99),
   Schlüsselrotation/-entzug für E2EE, Lasttests für Bulk-Delete/Reconciliation (Regressionstests
   exakt gegen die 6 Fehlerklassen aus Abschnitt 3), Backups, Disaster Recovery, Security-Review
   (devsecops-Skill), Chaos-/Recovery-Tests.

---

## 7. Testkonzept (Kurzfassung)

- Server: JUnit 5 + AssertJ, Testcontainers-Postgres, keine Mockito-Mocks für Domänenlogik
  (Fakes über Ports, analog `stoneai`-Stil).
- Regressionstests explizit für die 6 historischen Fehlerklassen (z. B. "Bulk-Delete von 500
  Dateien unter simulierter Server-Latenz darf keine Datei resurrectieren", "eine als
  unvollständig markierte Serverantwort darf nie eine Löschung auslösen").
- Plugin: Vitest + jsdom für Editor-Bindung, E2E-Suite gegen echten Server (zwei Clients,
  Cursor-Presence, Late-Joiner-Catchup) — Muster aus `stonesync/plugin/e2e` übernehmen.
- Intelligence-Worker: PDF-Fixtures zur Testzeit erzeugt (PDFBox), keine Binär-Fixtures im Repo
  (Muster aus `stoneai`); zusätzlich ein fester "goldener" Fragenkatalog zur Messung von
  SELMA-Recall@K und Zitationskorrektheit (s. 4.3).
- Sicherheitstests: ACL-Filterung in der SELMA-Kandidatensuche gegen Seitenkanal-Leaks
  (Score/Trefferanzahl/Timing darf keine verborgenen Notes verraten); E2EE-Spike aus Phase 2
  gegen Schlüsselentzug und Metadaten-Minimierung.
- CI: Build+Test je Modul (`platform-api`, `intelligence-worker`, `mcp-adapter`), Startup-Smoke-
  Test gegen das gebaute Artefakt (Muster aus `stoneai` CI: init/status/config-Roundtrip +
  Ablehnung eines geschützten Dokuments ohne API-Key).

---

## 8. Entscheidungen (Rev. 2 — am 2026-09-11 mit Tom final bestätigt, keine offenen Punkte mehr)

1. **Deployment-Schnitt Intelligence-Worker:** eigenes Deployable, kommuniziert über eine
   persistierte Job-Queue mit `platform-api` (Abschnitt 2). **Bestätigt: gemeinsames Postgres,
   getrennte Schemas/Tabellen-Owner** — geringerer Betriebsaufwand, ein Split auf zwei DBs bleibt
   später ohne Anwendungsumbau möglich, falls die Größenordnung es einmal erfordert.
2. **Mehr-Vault-/Cross-Vault-Betrieb:** **Bestätigt: verwerfen.** Nicht in Anforderungen.md
   gefordert, hätte Sichtbarkeit, Backlinks, E2EE und Löschsemantik erheblich verkompliziert.
   Mandanten-/Vault-Zugehörigkeit wird im Identitäts-/ACL-/Schlüsselmodell dennoch so angelegt,
   dass ein Vault sauber isoliert ist — ohne das Cross-Vault-Feature aktiv zu bauen.
3. **E2EE-Schlüsselverwaltung (Level 101):** **Bestätigt: optionaler, clientseitig erzeugter und
   verschlüsselt exportierter Recovery-Key**, den Nutzer aktiv anlegen können. Ein zusätzlicher
   Enterprise-Escrow-Modus bleibt möglich, aber als separat aktivierbarer Modus, nie stiller
   Default. Wer maximale Sicherheit über Wiederherstellbarkeit stellt, lässt den Recovery-Key
   einfach weg.
4. **Autoritative Repräsentation einer Note:** **Bestätigt: der Yjs-CRDT-Zustand ist Source of
   Truth**, konsistent mit dem "dummen Server"-Prinzip aus `stonesync` — die Markdown-Datei ist
   eine Serialisierung dieses Zustands, die DB speichert nur Snapshots/History. Bestimmt, wessen
   Zustand bei Konflikten gewinnt und wie Note-Level-Wechsel (insbes. der Übergang zu Level 101)
   technisch greifen: auf dem CRDT-Zustand, nicht auf der Datei.

Verbleibend, aber nicht blockierend für den Start von Phase 1–2 (können während Phase 2/5
mitentschieden werden, sobald der jeweilige Baustein ansteht):
- Welche Metadaten dürfen bei Level 101 server-sichtbar bleiben (Dateiname, Größe, Zugriffszeit)?
- Wie lange bleiben Tombstones/History/Audit/Quelldokumente aufbewahrt (Retention-Fristen)?
- Welche KI-Aktion darf automatisch publizieren, welche braucht menschliche Freigabe?

---

## Anhang: Rohes Review-Feedback

Die vollständigen, ungekürzten Antworten von Codex und Gemini 3.1 Pro (High) zu Rev. 1 dieses
Plans liegen dieser Überarbeitung zugrunde und sind auf Anfrage verfügbar (nicht in dieses
Dokument übernommen, um es lesbar zu halten) — die wichtigsten Punkte sind oben jeweils an der
betroffenen Stelle mit "Rev. 2" markiert eingearbeitet.
