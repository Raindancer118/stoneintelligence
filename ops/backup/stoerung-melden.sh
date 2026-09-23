#!/usr/bin/env bash
# Meldet eine fehlgeschlagene systemd-Einheit an Jules (Discord-Nachricht an Tom) - eine Sicherung,
# von der man erst im Ernstfall erfaehrt, dass sie seit Wochen nicht laeuft, ist keine.
# Aufruf ueber OnFailure= (systemd/stoneintelligence-stoerung@.service) oder von Hand:
#   ops/backup/stoerung-melden.sh stoneintelligence-backup.service
set -euo pipefail
EINHEIT="${1:-unbekannte Einheit}"
UMGEBUNG="${STOERUNG_ENV:-/home/murthag/stoneintelligence/.env}"
if [ -f "$UMGEBUNG" ]; then
  # Nur die zwei Zeilen lesen statt die Datei auszufuehren: dort stehen Kennwoerter mit Sonderzeichen.
  JULES_NOTIFY_URL="${JULES_NOTIFY_URL:-$(grep -m1 '^JULES_NOTIFY_URL=' "$UMGEBUNG" | cut -d= -f2- || true)}"
  JULES_NOTIFY_SECRET="${JULES_NOTIFY_SECRET:-$(grep -m1 '^JULES_NOTIFY_SECRET=' "$UMGEBUNG" | cut -d= -f2- || true)}"
fi
if [ -z "${JULES_NOTIFY_URL:-}" ] || [ -z "${JULES_NOTIFY_SECRET:-}" ]; then
  echo "JULES_NOTIFY_URL oder JULES_NOTIFY_SECRET fehlt - es wird nichts gemeldet." >&2
  exit 0
fi
LOG=$(journalctl -u "$EINHEIT" -n 12 --no-pager -o cat 2>/dev/null | tail -12 || echo "(kein Journal lesbar)")
KOERPER=$(python3 -c '
import json, sys
print(json.dumps({"quelle": "StoneIntelligence · " + sys.argv[1],
                  "text": f"❌ Fehlgeschlagen am {sys.argv[2]}\n\n```\n{sys.argv[3]}\n```"}))' "$EINHEIT" "$(date '+%d.%m.%Y %H:%M')" "$LOG")
if curl -fsS --max-time 20 -X POST "$JULES_NOTIFY_URL" -H "Content-Type: application/json" \
     -H "X-Jules-Secret: $JULES_NOTIFY_SECRET" -d "$KOERPER" -o /dev/null; then
  echo "Störung an Jules gemeldet: $EINHEIT"
else
  echo "Meldung an Jules fehlgeschlagen." >&2
fi
