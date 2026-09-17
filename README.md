# StoneIntelligence

Enterprise-Grade Kollaborations- und Wissensmanagement-Plattform für Obsidian: Live-Sync,
Note-Level-Policies, Rollen/Gruppen/ACLs und (ab Phase 5) KI-gestützte Wissens-Ingestion.

Architektur- und Phasenplanung: [`Plan.md`](Plan.md). Laufender Entwicklungsstand: [`Project.md`](Project.md).

Diese Anleitung deckt zwei unabhängige Installationen ab:

- **Server** — die drei Spring-Boot-Deployables (`platform-api`, `intelligence-worker`,
  `mcp-adapter`) + Postgres, per Docker Compose.
- **Client** — das Obsidian-Plugin (`plugin/`), das sich mit einem laufenden Server verbindet.

## Server-Installation

### Voraussetzungen

- Docker + Docker Compose
- Ein Postgres-Passwort für `STONEINTELLIGENCE_DB_PASSWORD` (siehe unten)

### Schnellstart

```bash
git clone git@github.com:Raindancer118/stoneintelligence.git
cd stoneintelligence

# Passwort setzen (Pflicht - docker-compose.yml bricht ohne dieses ab)
export STONEINTELLIGENCE_DB_PASSWORD="<sicheres-passwort>"

docker compose up -d --build
```

`docker-compose.yml` startet:

| Service | Port | Beschreibung |
|---|---|---|
| `postgres` | `127.0.0.1:5432` (nur lokal gebunden) | `pgvector/pgvector:pg17`, Datenbank `stoneintelligence` |
| `platform-api` | `8080` | Sync-Kern (WebSocket-Relay), Note-CRUD, Rollen/Gruppen/ACLs, Audit |
| `intelligence-worker` | `8081` | Ingestion/Knowledge-Graph (Phase 5+, aktuell nur Job-Outbox) |
| `mcp-adapter` | `8082` | Dünner MCP-Adapter über `platform-api`/`intelligence-worker` |

Datenbankschemata (`platform`, `worker`) werden beim Start von `platform-api` bzw.
`intelligence-worker` automatisch per Flyway migriert — kein manueller Migrationsschritt nötig.

### Umgebungsvariablen

| Variable | Pflicht | Default | Bedeutung |
|---|---|---|---|
| `STONEINTELLIGENCE_DB_USER` | nein | `stoneintelligence` | Postgres-Benutzer |
| `STONEINTELLIGENCE_DB_PASSWORD` | **ja** | — | Postgres-Passwort (Compose bricht ohne Wert ab) |
| `STONEINTELLIGENCE_OIDC_ISSUER_URI` | nein | leer | OIDC-Issuer für `platform-api` (Phase 3, Durchsetzung noch nicht scharf geschaltet) |

Für einen produktiven Betrieb diese Variablen über eine `.env`-Datei neben `docker-compose.yml`
setzen (von Compose automatisch geladen) — **niemals ins Repo committen**.

### Ohne Docker (lokale Entwicklung)

Voraussetzungen: JDK 25, Maven (oder `./mvnw`), ein laufendes Postgres (z. B. via
`docker compose up -d postgres`).

```bash
export STONEINTELLIGENCE_DB_URL="jdbc:postgresql://localhost:5432/stoneintelligence"
export STONEINTELLIGENCE_DB_USER="stoneintelligence"
export STONEINTELLIGENCE_DB_PASSWORD="<passwort>"

./mvnw -pl platform-api spring-boot:run
```

Analog für `intelligence-worker` und `mcp-adapter` (jeweils `-pl <modul> spring-boot:run`).

### Build & Tests

```bash
./mvnw test      # Unit-Tests (Fakes, kein Docker nötig)
./mvnw verify    # zusätzlich Integrationstests gegen echtes Postgres (Testcontainers, braucht laufenden Docker-Daemon)
```

## Client-Installation (Obsidian-Plugin)

### Voraussetzungen

- Node.js (für den Build)
- Ein laufender StoneIntelligence-Server (s. o.), erreichbar von dem Rechner, auf dem Obsidian läuft
- Obsidian ≥ 1.5.0

### Bauen

```bash
cd plugin
npm install
npm run build
```

Das erzeugt `plugin/main.js` neben dem bereits vorhandenen `manifest.json` und `styles.css`.

### In einen Obsidian-Vault installieren

Im Ziel-Vault (der Obsidian-Vault, der synchronisiert werden soll) einen Ordner
`.obsidian/plugins/stoneintelligence/` anlegen und folgende Dateien aus `plugin/` hineinkopieren:

```
manifest.json
main.js
styles.css
```

Danach in Obsidian: Einstellungen → Community-Plugins → „StoneIntelligence“ aktivieren.

### Alternativ: Installation über BRAT

Ab dem ersten Versions-Tag (`X.Y.Z`) baut eine GitHub Action automatisch ein Release mit
`manifest.json` + `main.js` + `styles.css` als Assets (`.github/workflows/plugin-release.yml`).
Damit lässt sich das Plugin auch über [BRAT](https://github.com/TfTHacker/obsidian42-brat)
installieren:

1. BRAT-Plugin in Obsidian installieren und aktivieren.
2. In BRAT: „Add Beta plugin“ → `Raindancer118/stoneintelligence` eintragen.
3. BRAT lädt die Release-Assets vom neuesten Release und hält sie automatisch aktuell.

### Plugin konfigurieren

In den Plugin-Einstellungen (Einstellungen → StoneIntelligence) folgende Felder setzen:

| Feld | Beschreibung | Beispiel |
|---|---|---|
| Platform API URL | HTTP-Adresse von `platform-api` | `http://localhost:8080` |
| Platform WS URL | WebSocket-Adresse von `platform-api` (gleicher Host/Port, anderes Schema) | `ws://localhost:8080` |
| Vault-ID | ID des Vaults auf dem Server (muss dort bereits angelegt sein) | — |
| Actor | Anzeigename/Kennung, unter der Änderungen dieses Clients im Audit-Trail erscheinen | `tom` |

Ohne gültige Vault-ID und einen erreichbaren Server bleibt die Live-Synchronisation inaktiv;
lokales Bearbeiten von Notizen im Vault funktioniert davon unabhängig weiterhin normal.

### Status & Befehle

Das Plugin zeigt seinen Verbindungsstatus in der Statusleiste unten rechts an
(z. B. `● StoneIntelligence: 2/3`, `⚠ StoneIntelligence: 1 Fehler` oder
`○ StoneIntelligence: nicht angemeldet`). Ein Klick darauf öffnet die
Status-Ansicht in der rechten Seitenleiste: sie zeigt Anmeldestatus,
konfigurierten Vault und pro Notiz den Live-Sync-Status (verbindet…,
synchronisiert, getrennt, Fehler).

Über die Befehlspalette (`Strg/Cmd+P`) stehen außerdem zur Verfügung:

- **StoneIntelligence: Status anzeigen** — öffnet die Status-Ansicht
- **StoneIntelligence: Anmelden** — startet den Login-Flow
- **StoneIntelligence: Alle Notizen neu synchronisieren**
- **StoneIntelligence: Aktive Notiz neu synchronisieren**

## Repo-Struktur

| Verzeichnis | Inhalt |
|---|---|
| `domain-core` | Framework-freie Domänenlogik (IDs, Note-Level-Policies) |
| `platform-api` | Sync-Kern, Identity/Authorization, Audit — Spring Boot 4 |
| `intelligence-worker` | Ingestion/Knowledge-Graph (im Aufbau) — Spring Boot 4 |
| `mcp-adapter` | MCP-Adapter über die beiden obigen Services — Spring Boot 4 (WebFlux) |
| `plugin/` | Obsidian-Plugin (TypeScript) |
| `docs/adr/` | Architecture Decision Records |
