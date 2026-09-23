#!/usr/bin/env bash
# Prueft backup.sh gegen eine echte Wegwerf-Postgres und Wegwerf-Verzeichnisse:
#   ops/backup/test-backup.sh
# Braucht Docker. Die Datenbank laeuft im Host-Netz (manche VPNs sperren die Docker-Bridge).
set -euo pipefail
HIER="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"; PORT=55432; NAME="si-backup-test-$$"
aufraeumen() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$TMP"; }
trap aufraeumen EXIT
fehler() { echo "TEST FEHLGESCHLAGEN: $*" >&2; exit 1; }

docker run -d --name "$NAME" --network host -e PGPORT=$PORT -e POSTGRES_USER=si -e POSTGRES_PASSWORD=x -e POSTGRES_DB=stoneintelligence postgres:17-alpine >/dev/null
until docker exec "$NAME" pg_isready -q -U si -d stoneintelligence 2>/dev/null; do sleep 1; done
sleep 1
docker exec "$NAME" psql -q -U si -d stoneintelligence -c "CREATE TABLE notizen (id int); INSERT INTO notizen VALUES (1), (2);"

mkdir -p "$TMP/dateien/ab/cd" "$TMP/db" "$TMP/kopie"
echo "pdf" > "$TMP/dateien/ab/cd/abcd1"; echo "bild" > "$TMP/dateien/ab/cd/abcd2"
lauf() {
  BACKUP_DB_CONTAINER="$NAME" BACKUP_DB_USER=si BACKUP_DB_ZIEL="$TMP/db" BACKUP_DATEIEN_QUELLE="$TMP/dateien" \
    BACKUP_DATEIEN_ZIEL="$TMP/kopie" BACKUP_TAEGLICH=3 BACKUP_MONATLICH=2 BACKUP_HEUTE="$1" "$HIER/backup.sh" >/dev/null
}

# 1. Dump entsteht, ist lesbar, Dateien sind kopiert
lauf 2026-09-20
[ -f "$TMP/db/stoneintelligence-2026-09-20.dump" ] || fehler "kein Dump"
docker cp "$TMP/db/stoneintelligence-2026-09-20.dump" "$NAME:/tmp/t.dump"
docker exec "$NAME" pg_restore -l /tmp/t.dump | grep -q "TABLE DATA public notizen" || fehler "Dump ohne Daten"
[ "$(cat "$TMP/kopie/aktuell/ab/cd/abcd1")" = "pdf" ] || fehler "Datei nicht kopiert"

# 2. Eine geloeschte Datei bleibt 30 Tage im Papierkorb der Sicherung, dann ist sie weg
rm "$TMP/dateien/ab/cd/abcd2"
lauf 2026-09-21
[ ! -f "$TMP/kopie/aktuell/ab/cd/abcd2" ] || fehler "geloeschte Datei noch in der aktuellen Kopie"
[ -f "$TMP/kopie/geloescht/2026-09-21/ab/cd/abcd2" ] || fehler "geloeschte Datei nicht aufbewahrt"
touch -d "40 days ago" "$TMP/kopie/geloescht/2026-09-21"
lauf 2026-09-22
[ ! -d "$TMP/kopie/geloescht/2026-09-21" ] || fehler "alte geloeschte Dateien nicht entfernt"

# 3. Rotation: die letzten 3 Tage plus die Monatsersten (2) bleiben
for tag in 2026-08-01 2026-09-01 2026-07-01; do lauf "$tag"; done
lauf 2026-09-23
BEHALTEN=$(ls "$TMP/db" | sort | tr '\n' ' ')
[ "$BEHALTEN" = "stoneintelligence-2026-08-01.dump stoneintelligence-2026-09-01.dump stoneintelligence-2026-09-21.dump stoneintelligence-2026-09-22.dump stoneintelligence-2026-09-23.dump " ] \
  || fehler "Rotation falsch: $BEHALTEN"

# 4. Fehlt die Quelle (Storage Box nicht gemountet), scheitert der Lauf laut
if BACKUP_DB_CONTAINER="$NAME" BACKUP_DB_USER=si BACKUP_DB_ZIEL="$TMP/db" BACKUP_DATEIEN_QUELLE="$TMP/gibtsnicht" \
   BACKUP_DATEIEN_ZIEL="$TMP/kopie" "$HIER/backup.sh" >/dev/null 2>&1; then fehler "fehlende Quelle nicht bemerkt"; fi

echo "backup.sh: alle Pruefungen bestanden"
