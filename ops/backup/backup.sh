#!/usr/bin/env bash
# Naechtliche Sicherung von StoneIntelligence (gehostet auf dorn).
#
# Gesichert wird, was nicht wiederherstellbar waere:
#   1. die Datenbank (Notizen als Yjs-Verlauf, Vaults, Rechte, Einladungen, KI-Aenderungen,
#      Datei-Metadaten) als pg_dump auf die Storage Box
#   2. die Datei-Inhalte (PDFs, Bilder - ADR 0009). Die liegen selbst schon auf der Storage Box,
#      deshalb geht ihre Kopie auf eine ANDERE Platte (Satteltasche): faellt die Box aus oder
#      loescht ein Fehler Dateien, gibt es sie noch.
#
# Grundsaetze (wie bei der Uniqua-Sicherung):
# - Scheitert etwas, bricht das Skript laut ab - ein stiller Teilerfolg waere schlimmer als nichts.
# - Der Dump wird erst geprueft, dann an seinen endgueltigen Namen gerueckt.
# - Geloeschte Dateien bleiben 30 Tage in der Sicherung (Versehen rueckholbar), danach sind sie
#   weg - Datensparsamkeit (DSGVO Art. 5 Abs. 1 lit. e).
set -euo pipefail

STACK_DIR="${BACKUP_STACK_DIR:-/home/murthag/stoneintelligence}"
DB_ZIEL="${BACKUP_DB_ZIEL:-/mnt/storagebox/stoneintelligence/db-backups}"
DATEIEN_QUELLE="${BACKUP_DATEIEN_QUELLE:-/mnt/storagebox/stoneintelligence/files}"
DATEIEN_ZIEL="${BACKUP_DATEIEN_ZIEL:-/mnt/saphirassatteltasche/stoneintelligence/files}"
DB_NAME="${BACKUP_DB_NAME:-stoneintelligence}"
TAEGLICH_BEHALTEN="${BACKUP_TAEGLICH:-30}"
MONATLICH_BEHALTEN="${BACKUP_MONATLICH:-12}"
GELOESCHT_TAGE="${BACKUP_GELOESCHT_TAGE:-30}"
HEUTE="${BACKUP_HEUTE:-$(date '+%Y-%m-%d')}"

meldung() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
abbruch() { printf '%s  FEHLER: %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; exit 1; }

# Container ueber Compose finden, nicht ueber einen festen Namen.
DB_CONTAINER="${BACKUP_DB_CONTAINER:-$(cd "$STACK_DIR" && docker compose ps -q postgres 2>/dev/null | head -1)}"
DB_USER="${BACKUP_DB_USER:-$(grep -m1 '^STONEINTELLIGENCE_DB_USER=' "$STACK_DIR/.env" 2>/dev/null | cut -d= -f2- || true)}"
[ -n "$DB_CONTAINER" ] || abbruch "Postgres-Container nicht gefunden."
[ -n "$DB_USER" ] || abbruch "Datenbanknutzer unbekannt."
[ -d "$DB_ZIEL" ] || abbruch "Ziel fuer die Datenbank fehlt: $DB_ZIEL (Storage Box gemountet?)"
[ -d "$DATEIEN_QUELLE" ] || abbruch "Dateien fehlen: $DATEIEN_QUELLE (Storage Box gemountet?)"
mkdir -p "$DATEIEN_ZIEL/aktuell" "$DATEIEN_ZIEL/geloescht" || abbruch "Ziel fuer die Dateien nicht anlegbar: $DATEIEN_ZIEL"

# 1. Datenbank
ENDGUELTIG="$DB_ZIEL/stoneintelligence-$HEUTE.dump"
VORLAEUFIG="$ENDGUELTIG.teil"
meldung "Sichere Datenbank nach $ENDGUELTIG …"
docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$VORLAEUFIG" || abbruch "pg_dump fehlgeschlagen."
# pg_restore muss im Dump springen koennen - also als Datei in den Container, nicht ueber stdin.
PRUEF_PFAD="/tmp/sicherung-pruefen-$$.dump"
docker cp "$VORLAEUFIG" "$DB_CONTAINER:$PRUEF_PFAD" >/dev/null
EINTRAEGE=$(docker exec "$DB_CONTAINER" sh -c "pg_restore -l $PRUEF_PFAD | grep -c '^[0-9]'; rm -f $PRUEF_PFAD" || echo 0)
[ "${EINTRAEGE:-0}" -gt 0 ] || { rm -f "$VORLAEUFIG"; abbruch "Dump ist nicht lesbar."; }
mv "$VORLAEUFIG" "$ENDGUELTIG"
meldung "Datenbank gesichert ($EINTRAEGE Eintraege, $(du -h "$ENDGUELTIG" | cut -f1))."

meldung "Raeume alte Datenbank-Sicherungen ab (taeglich: $TAEGLICH_BEHALTEN, monatlich: $MONATLICH_BEHALTEN) …"
BEHALTEN="$(mktemp)"
trap 'rm -f "$BEHALTEN"' EXIT
ls -1 "$DB_ZIEL"/stoneintelligence-*.dump 2>/dev/null | sort -r | head -n "$TAEGLICH_BEHALTEN" >> "$BEHALTEN" || true
ls -1 "$DB_ZIEL"/stoneintelligence-*-01.dump 2>/dev/null | sort -r | head -n "$MONATLICH_BEHALTEN" >> "$BEHALTEN" || true
for dump in "$DB_ZIEL"/stoneintelligence-*.dump; do
  [ -e "$dump" ] || continue
  grep -qxF "$dump" "$BEHALTEN" || { rm -f "$dump"; meldung "entfernt: $(basename "$dump")"; }
done

# 2. Datei-Inhalte (inhaltsadressiert, also unveraenderlich - rsync kopiert nur Neues)
meldung "Sichere Dateien nach $DATEIEN_ZIEL …"
rsync -a --delete --backup --backup-dir="$DATEIEN_ZIEL/geloescht/$HEUTE" \
  "$DATEIEN_QUELLE/" "$DATEIEN_ZIEL/aktuell/" || abbruch "rsync fehlgeschlagen."
QUELLE_ANZAHL=$(find "$DATEIEN_QUELLE" -type f -not -path '*/tmp/*' | wc -l)
KOPIE_ANZAHL=$(find "$DATEIEN_ZIEL/aktuell" -type f -not -path '*/tmp/*' | wc -l)
[ "$QUELLE_ANZAHL" -eq "$KOPIE_ANZAHL" ] || abbruch "Kopie unvollstaendig: $KOPIE_ANZAHL von $QUELLE_ANZAHL Dateien."
find "$DATEIEN_ZIEL/geloescht" -mindepth 1 -maxdepth 1 -type d -mtime +"$GELOESCHT_TAGE" -exec rm -rf {} +
meldung "Dateien gesichert ($KOPIE_ANZAHL)."
meldung "Sicherung fertig."
