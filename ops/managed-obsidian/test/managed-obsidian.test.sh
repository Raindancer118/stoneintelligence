#!/usr/bin/env bash
# Tests fuer managed-obsidian.sh ohne root und ohne Netz: chown/chflags sind Attrappen im PATH (sie protokollieren nur),
# GitHub ersetzt ein file://-Verzeichnis. Aufruf: bash ops/managed-obsidian/test/managed-obsidian.test.sh
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SCRIPT="$HERE/../managed-obsidian.sh"
ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
FAILED=0 PASSED=0

check() { # Beschreibung, Bedingung (als Befehl)
  local name=$1; shift
  if "$@" >/dev/null 2>&1; then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); echo "FAIL: $name"; fi
}
contains() { grep -qF -- "$2" "$1"; }
lacks() { ! grep -qF -- "$2" "$1"; }
eq() { [[ "$1" == "$2" ]]; }
sha() { sha256sum "$1" | cut -d' ' -f1; }

# --- Umgebung ------------------------------------------------------------------------------------------------
mkdir -p "$ROOT/bin" "$ROOT/gh"
for fake in chown chflags; do
  printf '#!/bin/sh\necho "%s $*" >> "%s/calls.log"\n' "$fake" "$ROOT" >"$ROOT/bin/$fake"; chmod +x "$ROOT/bin/$fake"
done
# curl protokolliert jeden Abruf und reicht ihn an das echte curl weiter
REAL_CURL=$(command -v curl)
printf '#!/bin/sh\necho "curl $*" >> "%s/calls.log"\nexec "%s" "$@"\n' "$ROOT" "$REAL_CURL" >"$ROOT/bin/curl"; chmod +x "$ROOT/bin/curl"
export PATH="$ROOT/bin:$PATH"
export MANAGED_OBSIDIAN_GITHUB_BASE="file://$ROOT/gh"
export MANAGED_OBSIDIAN_NATIVE_POLICY="$ROOT/etc/obsidian/policy.json"
export MANAGED_OBSIDIAN_ALLOW_NON_ROOT=1

# Ein "Release" im Verzeichnis: gh/<repo>/releases/download/<tag>/<datei>
release() { # repo version id [styles]
  local dir="$ROOT/gh/$1/releases/download/$2"
  mkdir -p "$dir"
  echo "console.log('$3 $2')" >"$dir/main.js"
  printf '{"id":"%s","name":"%s","version":"%s"}\n' "$3" "$3" "$2" >"$dir/manifest.json"
  [[ -n "${4:-}" ]] && echo ".$3{}" >"$dir/styles.css"
  return 0
}
plugin_json() { # repo version id
  local dir="$ROOT/gh/$1/releases/download/$2" styles=""
  [[ -f "$dir/styles.css" ]] && styles=",\"styles.css\":\"$(sha "$dir/styles.css")\""
  printf '{"id":"%s","repo":"%s","version":"%s","sha256":{"main.js":"%s","manifest.json":"%s"%s}}' \
    "$3" "$1" "$2" "$(sha "$dir/main.js")" "$(sha "$dir/manifest.json")" "$styles"
}
release acme/stone 0.34.0 stoneintelligence styles
release acme/stone 0.35.0 stoneintelligence styles
release liamcain/calendar 1.5.10 calendar
release other/kanban 2.0.51 kanban

VAULT="$ROOT/vault"
write_policy() { # Plugin-JSONs ...; setzt $POLICY
  POLICY="$ROOT/policy.json"
  local plugins; plugins=$(IFS=,; echo "$*")
  cat >"$POLICY" <<EOF
{"schemaVersion":1,"vaultPath":{"linux":"$VAULT","macos":"$VAULT"},"owner":"alice",
 "server":{"url":"https://si.example.com","oidcIssuer":"https://login.example.com/o/si/","oidcClientId":"plugin-client"},
 "plugins":[$plugins]}
EOF
}
SI_034() { plugin_json acme/stone 0.34.0 stoneintelligence; }
SI_035() { plugin_json acme/stone 0.35.0 stoneintelligence; }
CAL() { plugin_json liamcain/calendar 1.5.10 calendar; }
run() { bash "$SCRIPT" --policy "$POLICY" "$@"; }
reset() { rm -rf "$VAULT" "$ROOT/etc" "$ROOT/calls.log"; : >"$ROOT/calls.log"; }
tree_hash() { (cd "$VAULT" && find . -type f -exec sha256sum {} + | sort | sha256sum); }

# --- 1. Erstinstallation ------------------------------------------------------------------------------------------
reset; write_policy "$(SI_034)" "$(CAL)"
check "first run succeeds" run
check "community-plugins.json lists exactly the allowed ids" eq "$(jq -c . "$VAULT/.obsidian/community-plugins.json")" '["stoneintelligence","calendar"]'
check "main.js installed unchanged" eq "$(sha "$VAULT/.obsidian/plugins/calendar/main.js")" "$(sha "$ROOT/gh/liamcain/calendar/releases/download/1.5.10/main.js")"
check "optional styles.css installed for the plugin that has one" test -f "$VAULT/.obsidian/plugins/stoneintelligence/styles.css"
check "no styles.css for the plugin without one" test ! -e "$VAULT/.obsidian/plugins/calendar/styles.css"
DATA="$VAULT/.obsidian/plugins/stoneintelligence/data.json"
check "plugin data carries server, issuer and client" eq "$(jq -c '[.platformApiUrl,.oidcIssuerUrl,.oidcClientId]' "$DATA")" '["https://si.example.com","https://login.example.com/o/si/","plugin-client"]'
check "plugin data carries no tokens" eq "$(jq 'has("tokens")' "$DATA")" false
check "native policy pins the versions" eq "$(jq -c '.communityPlugins | map([.id,.version])' "$ROOT/etc/obsidian/policy.json")" '[["stoneintelligence","0.34.0"],["calendar","1.5.10"]]'
check "native policy locks dev tools and themes" eq "$(jq -c '[.devTools,.themes,.plugins,.schemaVersion]' "$ROOT/etc/obsidian/policy.json")" '[false,false,false,1]'

# --- Rechte (beabsichtigte Befehle) ------------------------------------------------------------------------------
check "vault folder goes to the user" contains "$ROOT/calls.log" "chown alice:"
check ".obsidian is root-owned" contains "$ROOT/calls.log" "chown root:"
check ".obsidian is sticky and world-writable" eq "$(stat -c %a "$VAULT/.obsidian")" 1777
check "plugin dirs are 755" eq "$(stat -c %a "$VAULT/.obsidian/plugins/calendar")" 755
check "plugin files are 644" eq "$(stat -c %a "$VAULT/.obsidian/plugins/calendar/main.js")" 644
check "community-plugins.json is 644" eq "$(stat -c %a "$VAULT/.obsidian/community-plugins.json")" 644
check "plugins are chowned recursively to root" contains "$ROOT/calls.log" "chown -R root:"
check "data.json goes back to the user" contains "$ROOT/calls.log" "plugins/calendar/data.json"
check "chflags is only used on macOS" lacks "$ROOT/calls.log" "chflags"

# --- 2. Wiederholung aendert nichts und laedt nichts -------------------------------------------------------------------
before=$(tree_hash); : >"$ROOT/calls.log"
check "second run succeeds" run
check "second run changes no file" eq "$before" "$(tree_hash)"
check "second run downloads nothing" lacks "$ROOT/calls.log" "curl"

# --- 3. Selbstheilung --------------------------------------------------------------------------------------------
echo "tampered" >>"$VAULT/.obsidian/plugins/calendar/main.js"
echo '["calendar","evil"]' >"$VAULT/.obsidian/community-plugins.json"
mkdir -p "$VAULT/.obsidian/plugins/evil"; echo x >"$VAULT/.obsidian/plugins/evil/main.js"
check "self-heal run succeeds" run
check "tampered plugin file is restored" eq "$(sha "$VAULT/.obsidian/plugins/calendar/main.js")" "$(sha "$ROOT/gh/liamcain/calendar/releases/download/1.5.10/main.js")"
check "community-plugins.json is restored" eq "$(jq -c . "$VAULT/.obsidian/community-plugins.json")" '["stoneintelligence","calendar"]'
check "plugin that is not on the list is removed" test ! -e "$VAULT/.obsidian/plugins/evil"
mkdir -p "$VAULT/.obsidian/plugins/keepme"
check "--keep-unlisted leaves other plugins alone" bash -c "bash '$SCRIPT' --policy '$POLICY' --keep-unlisted && test -d '$VAULT/.obsidian/plugins/keepme'"
rmdir "$VAULT/.obsidian/plugins/keepme" 2>/dev/null; rm -rf "$VAULT/.obsidian/plugins/keepme"

# --- 4. Einstellungen der Person bleiben ---------------------------------------------------------------------------
echo '{"tokens":{"accessToken":"secret"},"vaultId":"v-1","platformApiUrl":"https://old.example.com"}' >"$DATA"
check "rerun with existing plugin data succeeds" run
check "tokens survive" eq "$(jq -r .tokens.accessToken "$DATA")" secret
check "vault choice survives" eq "$(jq -r .vaultId "$DATA")" v-1
check "server address is corrected" eq "$(jq -r .platformApiUrl "$DATA")" "https://si.example.com"

# --- 5. Aktualisierung auf eine neue Version ---------------------------------------------------------------------------
write_policy "$(SI_035)" "$(CAL)"
check "upgrade succeeds" run
check "new version is installed" eq "$(jq -r .version "$VAULT/.obsidian/plugins/stoneintelligence/manifest.json")" 0.35.0

# --- 6. Falsche Pruefsumme: nichts wird veraendert -----------------------------------------------------------------------
reset; write_policy "$(SI_034)" "$(CAL)"; run >/dev/null
write_policy "$(SI_035 | sed 's/"main.js":"[0-9a-f]*"/"main.js":"0000000000000000000000000000000000000000000000000000000000000000"/')" "$(CAL)"
before=$(tree_hash)
check "wrong checksum fails" bash -c "! bash '$SCRIPT' --policy '$POLICY'"
check "wrong checksum leaves the installed version" eq "$before" "$(tree_hash)"

# --- 7. Trockenlauf ----------------------------------------------------------------------------------------------
reset; write_policy "$(SI_034)" "$(CAL)"
check "dry run succeeds" run --dry-run
check "dry run writes nothing" test ! -e "$VAULT"
check "dry run changes no ownership" lacks "$ROOT/calls.log" "chown"
check "dry run says what it would do" bash -c "bash '$SCRIPT' --policy '$POLICY' --dry-run | grep -q 'dry-run'"

# --- 8. Richtlinie des Servers -------------------------------------------------------------------------------------
reset
echo "token-123" >"$ROOT/token"
server_policy() { # JSON-Antwort des Servers
  write_policy "$(SI_034)" "$(CAL)"
  jq --arg t "$ROOT/token" '. + {serverPolicy:{vaultId:"v-1",tokenFile:$t}}' "$POLICY" >"$POLICY.new" && mv "$POLICY.new" "$POLICY"
  printf '#!/bin/sh\ncase "$*" in *plugin-policy*) echo "curl $*" >> "%s/calls.log"; %s ;; *) exec "%s" "$@" ;; esac\n' "$ROOT" "$1" "$REAL_CURL" >"$ROOT/bin/curl"
}
server_policy "echo '{\"mode\":\"ENFORCE\",\"allowed\":[{\"id\":\"nothing-else\",\"version\":null}],\"alwaysAllowed\":\"stoneintelligence\"}'"
check "run with a server policy succeeds" run
check "only the own plugin remains when the server allows nothing else" eq "$(jq -c . "$VAULT/.obsidian/community-plugins.json")" '["stoneintelligence"]'
check "the server is asked with the bearer token" contains "$ROOT/calls.log" "Authorization: Bearer token-123"
server_policy "echo '{\"mode\":\"ENFORCE\",\"allowed\":[{\"id\":\"calendar\",\"version\":\"1.5.10\"}]}'"
check "server policy allowing calendar installs it" bash -c "bash '$SCRIPT' --policy '$POLICY' && test -d '$VAULT/.obsidian/plugins/calendar'"
server_policy "exit 22"
before=$(tree_hash)
check "unreachable server policy still succeeds" run
check "unreachable server policy leaves the plugin set alone" eq "$before" "$(tree_hash)"
printf '#!/bin/sh\necho "curl $*" >> "%s/calls.log"\nexec "%s" "$@"\n' "$ROOT" "$REAL_CURL" >"$ROOT/bin/curl"

# --- 9. Ungueltige Richtlinien -------------------------------------------------------------------------------------
reset; write_policy "$(CAL)"
check "policy without the own plugin is refused" bash -c "! bash '$SCRIPT' --policy '$POLICY'"
check "policy without a valid checksum is refused" bash -c "echo '{\"schemaVersion\":1}' >'$ROOT/bad.json'; ! bash '$SCRIPT' --policy '$ROOT/bad.json'"
check "missing policy file is refused" bash -c "! bash '$SCRIPT' --policy '$ROOT/none.json'"
check "owner is required" bash -c "write() { :; }; jq 'del(.owner)' '$POLICY' >'$ROOT/p2.json'; unset SUDO_USER; ! bash '$SCRIPT' --policy '$ROOT/p2.json'"

echo "managed-obsidian: $PASSED bestanden, $FAILED fehlgeschlagen"
((FAILED == 0))
