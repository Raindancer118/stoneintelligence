#!/usr/bin/env bash
# Prueft die echten Rechte, die managed-obsidian.sh setzt - ohne root: in einem User-Namespace (unshare --map-auto) ist
# der Aufrufer "root" und UID 1000 eine zweite, unprivilegierte Person. Wird uebersprungen, wo das nicht geht.
# Aufruf: bash ops/managed-obsidian/test/userns.test.sh
set -uo pipefail

if [[ "${1:-}" != "--inside" ]]; then
  if ! unshare --map-auto --map-root-user true 2>/dev/null || ! command -v setpriv >/dev/null; then
    echo "userns: uebersprungen (unshare --map-auto oder setpriv fehlt)"; exit 0
  fi
  exec unshare --map-auto --map-root-user bash "${BASH_SOURCE[0]}" --inside
fi

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SCRIPT="$HERE/../managed-obsidian.sh"
W=$(mktemp -d /tmp/managed-obsidian-userns-XXXXXX); chmod 755 "$W"
trap 'rm -rf "$W"' EXIT
FAILED=0
as_user() { setpriv --reuid 1000 --regid 1000 --clear-groups "$@"; }
expect() { # erlaubt|verboten, Beschreibung, Befehl...
  local want=$1 name=$2; shift 2
  local got=verboten; as_user "$@" >/dev/null 2>&1 && got=erlaubt
  [[ "$got" == "$want" ]] || { FAILED=$((FAILED + 1)); echo "FAIL: $name (erwartet $want)"; }
}

mkdir -p "$W/gh/acme/stone/releases/download/1.0.0" "$W/gh/acme/cal/releases/download/1.0.0"
for pair in "stone:stoneintelligence" "cal:calendar"; do
  dir="$W/gh/acme/${pair%%:*}/releases/download/1.0.0"
  echo "x" >"$dir/main.js"; printf '{"id":"%s","version":"1.0.0"}\n' "${pair##*:}" >"$dir/manifest.json"
done
plugin() { printf '{"id":"%s","repo":"acme/%s","version":"1.0.0","sha256":{"main.js":"%s","manifest.json":"%s"}}' "$1" "$2" \
  "$(sha256sum "$W/gh/acme/$2/releases/download/1.0.0/main.js" | cut -d' ' -f1)" "$(sha256sum "$W/gh/acme/$2/releases/download/1.0.0/manifest.json" | cut -d' ' -f1)"; }
cat >"$W/policy.json" <<EOF
{"schemaVersion":1,"vaultPath":{"linux":"$W/vault"},"owner":"1000","server":{"url":"https://x","oidcIssuer":"https://i","oidcClientId":"c"},
 "plugins":[$(plugin stoneintelligence stone),$(plugin calendar cal)]}
EOF

MANAGED_OBSIDIAN_GITHUB_BASE="file://$W/gh" MANAGED_OBSIDIAN_NATIVE_POLICY="$W/native/policy.json" \
  bash "$SCRIPT" --policy "$W/policy.json" >/dev/null || { echo "FAIL: Lauf"; exit 1; }

OBS="$W/vault/.obsidian"
expect erlaubt  "Person legt Notiz im Vault an"                 sh -c "echo hi > '$W/vault/Note.md'"
expect erlaubt  "Obsidian legt app.json in .obsidian an"        sh -c "echo '{}' > '$OBS/app.json'"
expect erlaubt  "Plugin speichert seine data.json"              sh -c "echo '{\"a\":1}' > '$OBS/plugins/calendar/data.json'"
expect verboten "community-plugins.json beschreiben"            sh -c "echo '[]' > '$OBS/community-plugins.json'"
expect verboten "community-plugins.json ersetzen"               sh -c "rm -f '$OBS/community-plugins.json'"
expect verboten "plugins/ umbenennen"                           mv "$OBS/plugins" "$OBS/plugins.bak"
expect verboten "neuen Plugin-Ordner anlegen"                   mkdir "$OBS/plugins/evil"
expect verboten "main.js eines Plugins aendern"                 sh -c "echo y >> '$OBS/plugins/calendar/main.js'"
expect verboten "Plugin-Ordner loeschen"                        rm -rf "$OBS/plugins/calendar"
expect verboten "data.json loeschen"                            rm -f "$OBS/plugins/calendar/data.json"

(( FAILED == 0 )) && echo "userns: alle Rechte wie vorgesehen" || { echo "userns: $FAILED fehlgeschlagen"; exit 1; }
