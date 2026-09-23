# ADR 0009: Dateien (PDFs, Bilder, Anhänge) synchronisieren

Status: Angenommen (2026-09-23)

## Kontext

Bisher synchronisiert StoneIntelligence nur Markdown-Notizen. Tom möchte, dass auch PDFs,
Bilder und andere Dateien im Vault synchronisiert werden – „genauso gut wie die MD-Dateien“:
Anlegen, Ändern, Umbenennen/Verschieben, Löschen samt Löschkonflikt, offline, alle Geräte,
Rechte und Pfadregeln. Entscheidungen von Tom (23.09.2026): Die Dateien liegen für die
gehostete Instanz auf der **Storage Box** (auf dorn als `/mnt/storagebox`, CIFS, 1 TB);
Grenzen **200 MB je Datei, 5 GB je Vault**.

## Entscheidungen

1. **Dateien sind Einträge derselben Tabelle wie Notizen** (`platform.notes`, neue Spalte
   `kind` = `NOTE` | `FILE`). Damit gelten für sie ohne Doppelung dieselben stabilen Ids,
   Pfade, Umbenennen, Tombstones, Ordner, Pfadregeln, Levels, Audit und Vault-Ankündigungen.
   Yjs gibt es für Dateien nicht: Inhalts- und Sync-Endpunkte für Notizen lehnen Datei-Ids ab.
2. **Inhalt als Versionen, Bytes inhaltsadressiert.** `platform.file_versions` hält je Datei
   fortlaufende Revisionen (SHA-256, Größe, Typ, wer, wann). Die Bytes selbst liegen in einem
   `BlobStore` unter ihrem SHA-256 – gleiche Inhalte nur einmal. Implementierung für den Betrieb:
   Dateisystem unter `STONEINTELLIGENCE_FILE_STORAGE_DIR` (gehostet: Storage Box). Geschrieben
   wird in eine temporäre Datei im selben Verzeichnis, geprüft (Größe, Hash) und erst dann
   umbenannt; eine halb geschriebene Datei wird nie sichtbar. Fällt die Storage Box aus, liefern
   nur die Datei-Endpunkte 503 – Notizen laufen weiter.
3. **Konflikte ohne Zusammenführen.** Binärdateien lassen sich nicht mergen. Hochladen geht nur
   mit der Revision, auf der die Änderung beruht (`If-Match`); passt sie nicht (409), behält das
   Plugin seine Fassung als Konfliktkopie (wie bei Notizen) und übernimmt die des Servers. So geht
   keine Fassung verloren.
4. **Löschen wie bei Notizen**, inklusive Löschentscheidung (15-Sekunden-Dialog), wenn die Datei
   hier seit dem letzten Abgleich verändert wurde – erkannt am Inhalt (SHA-256), nicht an
   Zeitstempeln.
5. **Ältere Plugins sehen keine Dateien.** Die Notizliste liefert Dateien nur auf Anfrage
   (`kinds=note,file`), Ankündigungen zu Dateien gehen nur an Verbindungen, die sie abonniert
   haben (Frame 13). Ein älteres Plugin würde eine Datei sonst als leere Notiz anlegen.
6. **Grenzen und Aufbewahrung:** 200 MB je Datei (`STONEINTELLIGENCE_FILE_MAX_MB`), 5 GB je
   Vault (`STONEINTELLIGENCE_FILE_QUOTA_MB`), beides konfigurierbar; größere Dateien bleiben
   lokal und das Plugin sagt es. Aufbewahrt wird nur die aktuelle Fassung: ersetzte Versionen und
   die Bytes gelöschter Dateien werden nach 24 Stunden entfernt (Datensparsamkeit, DSGVO Art. 5
   Abs. 1 lit. e) – eine Wiederherstellung älterer Fassungen gibt es nicht, auf den Geräten landen
   gelöschte Dateien im Papierkorb von Obsidian. Die Karenzzeit sorgt dafür, dass ein
   gleichzeitiger Upload desselben Inhalts seine Bytes nie verliert.
7. **Ausliefern ohne Ausführen:** Downloads immer als `attachment` mit `nosniff` und
   `Content-Security-Policy: sandbox` – eine hochgeladene SVG/HTML-Datei kann so nie im Kontext
   der API oder des Dashboards Skripte ausführen. Das Dashboard zeigt Vorschauen über Blob-URLs.
8. **Levels:** Dateien haben wie Notizen ein Level (Standard 1). Level 100 (keine
   Synchronisation) und 101 (Ende-zu-Ende) sind für Dateien in diesem Schritt nicht vorgesehen –
   solche Dateien lädt das Plugin nicht hoch.

## Folgen

- Das Plugin braucht einen eigenen, rein testbaren Abgleichsplan für Dateien (Inhalts-Hashes
  statt Yjs-Zustand) und nutzt `readBinary`/`createBinary`/`modifyBinary` von Obsidian.
- Backups: Die Datei-Bytes liegen auf der Storage Box, die Metadaten in Postgres – eine
  Wiederherstellung braucht beides.
