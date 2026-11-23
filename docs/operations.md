# Betrieb

Stack: `docker-compose.yml` (postgres, platform-api, intelligence-worker, mcp-adapter). Hosted auf dorn unter
`/home/murthag/stoneintelligence`.

## Health und Metriken

| Dienst | Port (intern) | Endpunkte |
| --- | --- | --- |
| platform-api | 8080 | `/actuator/health` (inkl. DB), `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info` (oeffentlich, ohne Details) |
| intelligence-worker | 8081 | wie oben, zusaetzlich `/actuator/prometheus` (Port wird nicht veroeffentlicht) |
| mcp-adapter | 8082 | `/actuator/health`, Probes |

`env`, `configprops` u. a. sind nicht exponiert. Jeder Container hat einen `HEALTHCHECK` (`ops/docker/healthcheck.sh`);
Status: `docker compose ps`. Logs: `docker compose logs -f <dienst>` (Rotation 20 MB x 5). JSON-Logs:
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` in die Umgebung des Dienstes. Der Worker beendet sich bei OutOfMemory
(`-XX:+ExitOnOutOfMemoryError`), Compose startet ihn neu; die Lease (10 min) gibt den Job frei, die gespeicherten
Modellantworten (`ai-resume`) bleiben.

## Sicherung

Naechtlich 03:40 per systemd-Timer (`ops/backup/systemd`): `pg_dump -Fc` auf die Storage Box (30 taegliche + 12
monatliche Staende), Datei-Inhalte per rsync auf eine zweite Platte (geloeschte Dateien 30 Tage). Fehler loesen
`stoneintelligence-stoerung@` aus. `ops/backup/test-backup.sh` (CI) prueft Dump, Aufbewahrung und eine echte
Wiederherstellung in eine frische Datenbank.

## Restore

Zuerst den Zustand sichern, den man ueberschreibt (`pg_dump` wie in `backup.sh`), nichts loeschen ohne Freigabe.

```bash
cd /home/murthag/stoneintelligence
docker compose stop platform-api intelligence-worker mcp-adapter
DB=$(docker compose ps -q postgres)
DUMP=/mnt/storagebox/stoneintelligence/db-backups/stoneintelligence-YYYY-MM-DD.dump
docker cp "$DUMP" "$DB:/tmp/restore.dump"
# in eine NEUE Datenbank, Daten erst nach Pruefung umschalten
docker exec "$DB" createdb -U "$STONEINTELLIGENCE_DB_USER" restore_check
docker exec "$DB" pg_restore -U "$STONEINTELLIGENCE_DB_USER" -d restore_check --no-owner --exit-on-error /tmp/restore.dump
docker exec "$DB" psql -U "$STONEINTELLIGENCE_DB_USER" -d restore_check -c 'SELECT count(*) FROM platform.vaults'
# ok? dann umbenennen: stoneintelligence -> stoneintelligence_old, restore_check -> stoneintelligence
docker exec "$DB" rm /tmp/restore.dump
docker compose start platform-api intelligence-worker mcp-adapter
```

Datei-Inhalte: `rsync -a /mnt/saphirassatteltasche/stoneintelligence/files/aktuell/ <Ziel>/` (versehentlich
Geloeschtes liegt unter `geloescht/<Datum>/`). Nach dem Restore pruefen, dass jede Datei-Metadatenzeile ihre Datei hat.

## Update und Rollback

1. Vor jedem Update ein frischer Dump (`sudo systemctl start stoneintelligence-backup.service`).
2. Deployment ueber die CI/CD-Pipeline bzw. wie in `Project.md` beschrieben; nie `.env`/`worker.env` ueberschreiben.
3. Flyway-Migrationen (`platform-api/src/main/resources/db/migration/platform`) laufen beim Start und sind nur
   vorwaerts. Rollback = vorheriges Image-Tag **und** Dump von vor dem Update einspielen (Restore oben); ein altes
   Image gegen ein neueres Schema startet nicht (`ddl-auto: validate`).
4. Images sind per Digest gepinnt, Dependabot schlaegt Updates vor.

## Mehrere Instanzen

`STONEINTELLIGENCE_CLUSTER=true` auf jeder platform-api-Instanz, alle an derselben Datenbank (ADR 0013, Schritt 7).
Jede Instanz belegt eine Pool-Verbindung dauerhaft fuer `LISTEN`. Der Load Balancer braucht keine Sticky Sessions;
WebSockets muessen durchgereicht werden. Rate-Limits gelten je Instanz.

## Secrets

Nur in `.env` / `worker.env` auf dem Server (nicht im Repo, nicht im Image; `.dockerignore` schliesst sie aus):
`STONEINTELLIGENCE_DB_PASSWORD`, `STONEINTELLIGENCE_AI_WORKER_TOKEN`, `STONEINTELLIGENCE_SCIM_TOKEN`,
`STONEINTELLIGENCE_AUTHENTIK_TOKEN`, `STONEINTELLIGENCE_MAIL_PASSWORD`, Modell-Schluessel im Worker
(`GROQ_API_KEY`, `GOOGLE_API_KEY`, `AI_SERVICE_<ID>_KEYS`), `maven-settings.xml` (nur Build, als BuildKit-Secret).
Der Worker sieht keine DB-Secrets (ADR 0008). Die `.env` ist nicht wiederherstellbar: eine Kopie gehoert in den
Passwortmanager. Token-Wechsel ohne Ausfall: `STONEINTELLIGENCE_SCIM_TOKEN` akzeptiert mehrere, durch Komma getrennt.
