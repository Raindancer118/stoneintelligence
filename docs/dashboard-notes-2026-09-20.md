# Notizen im Kunden-Dashboard

Die Webapp hat einen eigenen Notizbereich mit lesbarer Markdown-Ansicht und einem Editor.
Notizen lassen sich anlegen, nach Pfad durchsuchen und nach Ordner filtern, umbenennen,
verschieben, als Markdown exportieren und nach Bestätigung löschen. Der Änderungsverlauf
zeigt Anlage, Pfadänderungen und Speichervorgänge im Browser. Die Verwaltung bleibt separat
und wird nur bei entsprechenden Vault-Rechten angeboten.

## Daten und Speichern

- `GET /api/v1/vaults/{vaultId}/permissions`: eigene effektive Vault-Rechte.
- `GET .../notes/{noteId}/content`: `{ revision, updates }`, Updates sind Base64-codierte
  Yjs-Update-Blobs aus dem vorhandenen SnapshotStore.
- `POST .../notes/{noteId}/content`: `{ expectedRevision, update }`. Antwort `{ revision }`;
  HTTP 409, falls inzwischen eine andere Änderung gespeichert wurde. In diesem Fall bleibt
  der Entwurf im Editor. Kein automatisches Überschreiben und kein automatisches Verwerfen.
- Der Compare-and-append ist eine einzelne SQL-Anweisung mit dem vorhandenen Unique-Key
  `(note_id, server_sequence)`. Zwei Repository-Instanzen können dieselbe Version nicht beide
  erfolgreich fortschreiben. Normale WebSocket-Appends teilen diesen Key.
- Speichern verteilt das persistierte Update an verbundene Plugin-Sessions. Der Browser
  aktualisiert seinen gelesenen Inhalt ausdrücklich per Aktion, nicht durch eine Live-Bindung.
- Keine neue Migration, keine zusätzliche Kopie des Markdown-Inhalts in der Datenbank.

## Grenzen und Schutz

Die Suche umfasst Titel und Pfad der geladenen Metadaten, keine Volltextsuche über alle
Inhalte. Weitere Listen-Seiten werden bei Bedarf geladen, ebenso Notizinhalte. Große
Yjs-Historien werden beim Öffnen vollständig rekonstruiert; Snapshot-Kompaktierung ist
weiterhin ein eigener Ausbaupunkt. Entwürfe werden nicht dauerhaft im Browser gespeichert.

Vault- und Pfadrechte werden serverseitig geprüft. Gesperrte Pfade werden aus Listen gefiltert,
ohne Cursor/complete zu verfälschen; eine leere Seite kann deshalb weitere Seiten haben.
Auch historische Pfade im Audit werden geprüft. Rename prüft Quelle und Ziel. Unsichere
Dateipfade und doppelte Namen werden verständlich abgewiesen. Level-101-/Ciphertext-Inhalte
werden im Browser nicht geöffnet. Schreibende Content-Requests sind auf 2 MiB pro Update
begrenzt. Wie der existierende Relay interpretiert die API die Yjs-Bytes nicht semantisch.

Markdown wird mit [Marked](https://marked.js.org/) gerendert und mit
[DOMPurify](https://github.com/cure53/DOMPurify) bereinigt. Externe Medien, Styles und Formulare
werden entfernt. So löst eine Vorschau keine Bildanfragen an fremde Server aus.

## Prüfung und Rollout

- Java: Unit-, PostgreSQL-, Startup-, HTTP- und WebSocket-Tests. Zusätzliche Szenarien für
  bedingtes Speichern, parallele Repository-Instanzen, Relay-Zustellung, reine Leser,
  geschützte Ordner, verschlüsselte Inhalte und Pfadvalidierung.
- Webapp: Komponenten-/Yjs-/Sanitizer-Tests, Svelte-/TypeScript-Prüfung und Produktionsbuild.
- Playwright mit isolierten synthetischen Daten: Lesen/Speichern/Wiederöffnen, Konflikt und
  Navigationsschutz, Neuanlage, Umbenennen/Löschen sowie mobile Ansicht ohne Überlauf.
  Browserprüfungen laufen auch in GitHub Actions. Sie verwenden keine Produktionskonten.

API vor der Webapp ausrollen; die neuen Endpunkte sind additiv und benötigen keinen
Plugin-Release. Ein Produktionsdeployment ist nicht Bestandteil dieses Commits.
