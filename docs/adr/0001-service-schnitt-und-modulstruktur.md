# ADR 0001: Service-Schnitt und Modulstruktur

Status: Angenommen (2026-09-11)

## Kontext

StoneIntelligence besteht aus einem latenzkritischen Sync-Pfad (Yjs-Relay, Cursor-Presence)
und einem rechenintensiven, potenziell langsamen KI-Ingestion-Pfad (Chunking, Extraktion,
Embeddings, Knowledge-Graph-Pflege). Ein früherer Entwurf vermischte diese als "ein Modul,
optional ein eigener Dienst" - das führt leicht zu einem verteilten Monolithen mit doppelter
Auth-/DB-Logik (Kritik aus dem Codex-Review zu Plan.md Rev. 1).

## Entscheidung

Klare Trennung in vier Java-Module und drei eigenständige Deployables:

- `domain-core`: bewusst klein, framework-frei (kein Spring, keine JPA). Nur stabile IDs
  (Vault/Note/Document) und das Note-Level-Policy-Profil (s. ADR 0003).
- `platform-api`: eigenes Deployable. Sync-Kern (Yjs-Relay), Identity/OIDC, Authorization
  (Rollen/Gruppen, Ordner- und Themen-ACLs), Audit, Attachments, History, Rate-Limiting.
- `intelligence-worker`: eigenes Deployable von Anfang an, nicht "optional Modul". Ingestion,
  Wissens-/Provenienzmodell, Knowledge Graph, Embeddings, KI-Change-Sets. Kommuniziert mit
  `platform-api` ausschließlich über eine persistierte Job-Queue (Outbox-Tabelle in Postgres).
- `mcp-adapter`: dünner Protokoll-Adapter für Agenten, ohne eigene Auth-/ACL-/Such-Logik. Ruft
  ausschließlich die Application-APIs von `platform-api` und `intelligence-worker` auf.

`contracts/` (OpenAPI + JSON-Schema) verhindert Schema-Drift zwischen den Deployables sowie
`webapp` und `plugin` (beide TypeScript), da `domain-core` als Java-Bibliothek für sie nicht
nutzbar ist.

## Konsequenzen

- Der Sync-Pfad bleibt frei von KI-Workload-Spitzen; beide Deployables skalieren unabhängig.
- Mehr Betriebsaufwand als ein Monolith (drei laufende Prozesse statt einem), akzeptiert für
  die Zuverlässigkeits- und Sicherheitsgrenze, die dadurch entsteht.
- `mcp-adapter` kann nie eine vierte Parallel-Implementierung von Auth/ACL/Suche werden, weil
  er strukturell keinen eigenen Datenzugriff hat.

Siehe Plan.md Abschnitt 2 und Abschnitt 8.1.
