#!/usr/bin/env bash
# Verwaltetes Obsidian (Linux, macOS): Firmen-Vault anlegen, genau die erlaubten Plugins installieren, Rechte setzen.
# Als root per MDM (Intune, Jamf) oder Ansible ausfuehren, gern als Timer zur Selbstheilung. Mehrfaches Ausfuehren
# ist harmlos. Entwurf und Grenzen: docs/adr/0019-plugin-steuerung.md, Anleitung: README.md.
set -euo pipefail

POLICY="" DRY_RUN=0 KEEP_UNLISTED=0
GITHUB_BASE="${MANAGED_OBSIDIAN_GITHUB_BASE:-https://github.com}"
STATE_DIR="${MANAGED_OBSIDIAN_STATE_DIR:-/var/lib/managed-obsidian}"

usage() {
  cat <<'EOF'
Aufruf: managed-obsidian.sh --policy DATEI [--dry-run] [--keep-unlisted]
  --policy DATEI     Richtliniendatei (JSON, Vorlage: policy.example.json)
  --dry-run          nur anzeigen, nichts veraendern
  --keep-unlisted    Plugin-Ordner, die nicht in der Richtlinie stehen, nicht entfernen
Benoetigt: jq, curl, sha256sum oder shasum. Laeuft als root.
EOF
}

log() { printf '%s\n' "managed-obsidian: $*"; }
die() { printf '%s\n' "managed-obsidian: Fehler: $*" >&2; exit 1; }
# Jede Aenderung am Dateisystem laeuft hier durch; --dry-run zeigt sie nur an.
act() { if ((DRY_RUN)); then log "[dry-run] $*"; else "$@"; fi; }

os_name() { case "$(uname -s)" in Darwin) echo macos ;; Linux) echo linux ;; *) die "nicht unterstuetztes System: $(uname -s)" ;; esac; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# --- Richtlinie ---------------------------------------------------------------------------------------------

validate_policy() {
  jq -e '.schemaVersion == 1' "$POLICY" >/dev/null 2>&1 || die "Richtlinie: schemaVersion 1 erwartet"
  jq -e '(.server.url | type == "string") and (.server.oidcIssuer | type == "string") and (.server.oidcClientId | type == "string")' "$POLICY" >/dev/null \
    || die "Richtlinie: server.url, server.oidcIssuer und server.oidcClientId fehlen"
  jq -e '.plugins | type == "array" and any(.[]; .id == "stoneintelligence")' "$POLICY" >/dev/null \
    || die "Richtlinie: plugins muss das Plugin \"stoneintelligence\" enthalten"
  jq -e 'all(.plugins[]; (.id | test("^[A-Za-z0-9._-]+$")) and (.repo | test("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$"))
         and (.version | test("^[A-Za-z0-9._+-]+$")) and (.sha256["main.js"] | test("^[0-9a-f]{64}$"))
         and (.sha256["manifest.json"] | test("^[0-9a-f]{64}$")))' "$POLICY" >/dev/null \
    || die "Richtlinie: jedes Plugin braucht id, repo (Besitzer/Name), version und sha256 fuer main.js und manifest.json"
}

# Optional: der Server sagt, welche der Plugins aus der Richtlinie erlaubt sind (ADR 0019). Ohne Antwort aendert der
# Lauf den Plugin-Bestand nicht, repariert aber weiter Rechte und Dateien.
SERVER_POLICY_OK=1
apply_server_policy() {
  POLICY_EFFECTIVE="$POLICY"
  jq -e '.serverPolicy' "$POLICY" >/dev/null 2>&1 || return 0
  local url vault token_file response
  url=$(jq -r '.server.url' "$POLICY"); vault=$(jq -r '.serverPolicy.vaultId' "$POLICY"); token_file=$(jq -r '.serverPolicy.tokenFile' "$POLICY")
  if [[ ! -r "$token_file" ]] || ! response=$(curl -fsS --max-time 20 -H "Authorization: Bearer $(<"$token_file")" "${url%/}/api/v1/vaults/${vault}/plugin-policy") \
     || ! jq -e '.mode' <<<"$response" >/dev/null 2>&1; then
    log "Warnung: Richtlinie des Servers nicht erreichbar, der Plugin-Bestand bleibt unveraendert"
    SERVER_POLICY_OK=0
    return 0
  fi
  POLICY_EFFECTIVE="$WORK/effective-policy.json"
  jq --argjson server "$response" '
    if $server.mode == "ENFORCE" then
      .plugins |= map(select(.id == "stoneintelligence" or (. as $p | any($server.allowed[]; .id == $p.id and (.version == null or .version == $p.version)))))
    else . end' "$POLICY" >"$POLICY_EFFECTIVE"
}

# --- Besitzer und Pfade ---------------------------------------------------------------------------------------

resolve_vault() {
  VAULT=$(jq -r --arg os "$OS" '.vaultPath | if type == "object" then .[$os] // empty else . end' "$POLICY")
  [[ -n "$VAULT" && "$VAULT" == /* ]] || die "Richtlinie: vaultPath fehlt oder ist nicht absolut (fuer $OS)"
  OBS="$VAULT/.obsidian"; PLUGINS="$OBS/plugins"
}

resolve_owner() {
  OWNER=$(jq -r '.owner // empty' "$POLICY")
  [[ -n "$OWNER" ]] || OWNER="${SUDO_USER:-}"
  [[ -n "$OWNER" ]] || { [[ -d "$VAULT" ]] && OWNER=$(stat -c %U "$VAULT" 2>/dev/null || stat -f %Su "$VAULT"); }
  [[ -n "$OWNER" && "$OWNER" != root ]] || die "Richtlinie: owner (Benutzer, der im Vault arbeitet) fehlt"
  ROOT_GROUP=$(id -gn 0)
  OWNER_GROUP=$(id -gn "$OWNER" 2>/dev/null || echo "$OWNER")
}

native_policy_path() {
  if [[ -n "${MANAGED_OBSIDIAN_NATIVE_POLICY:-}" ]]; then echo "$MANAGED_OBSIDIAN_NATIVE_POLICY"
  elif [[ "$OS" == macos ]]; then echo "/Library/Application Support/Obsidian/policy.json"
  else echo "/etc/obsidian/policy.json"; fi
}

# --- Bausteine ------------------------------------------------------------------------------------------------

unlock() {
  [[ "$OS" == macos ]] || return 0
  [[ -e "$PLUGINS" ]] && act chflags -R nouchg "$PLUGINS"
  [[ -e "$OBS/community-plugins.json" ]] && act chflags nouchg "$OBS/community-plugins.json"
  return 0
}

write_file() { # Pfad, Inhalt auf stdin
  if ((DRY_RUN)); then log "[dry-run] schreibe $1"; cat >/dev/null; else cat >"$1"; fi
}

plugin_current() { # id version -> 0, wenn Dateien und Hashes schon stimmen
  local id=$1 dir="$PLUGINS/$1" asset expected
  [[ -d "$dir" ]] || return 1
  for asset in main.js manifest.json styles.css; do
    expected=$(jq -r --arg id "$id" --arg a "$asset" '.plugins[] | select(.id == $id) | .sha256[$a] // empty' "$POLICY_EFFECTIVE")
    if [[ -n "$expected" ]]; then
      [[ -f "$dir/$asset" && "$(sha256_of "$dir/$asset")" == "$expected" ]] || return 1
    else
      [[ ! -e "$dir/$asset" ]] || return 1
    fi
  done
  return 0
}

install_plugin() { # Index in der Plugin-Liste
  local entry id repo version tag asset expected got tmp
  entry=$(jq -c --argjson i "$1" '.plugins[$i]' "$POLICY_EFFECTIVE")
  id=$(jq -r .id <<<"$entry"); repo=$(jq -r .repo <<<"$entry"); version=$(jq -r .version <<<"$entry"); tag=$(jq -r '.tag // .version' <<<"$entry")
  if plugin_current "$id"; then log "$id $version: unveraendert"; return 0; fi
  log "$id $version: installiere"
  tmp=$(mktemp -d "$WORK/dl-XXXXXX")
  for asset in main.js manifest.json styles.css; do
    expected=$(jq -r --arg a "$asset" '.sha256[$a] // empty' <<<"$entry")
    [[ -n "$expected" ]] || continue
    curl -fsSL --max-time 120 -o "$tmp/$asset" "$GITHUB_BASE/$repo/releases/download/$tag/$asset" || die "$id: $asset nicht ladbar ($repo $tag)"
    got=$(sha256_of "$tmp/$asset")
    [[ "$got" == "$expected" ]] || die "$id: Pruefsumme von $asset stimmt nicht (erwartet $expected, erhalten $got) - nichts installiert"
  done
  [[ "$(jq -r .id "$tmp/manifest.json")" == "$id" && "$(jq -r .version "$tmp/manifest.json")" == "$version" ]] \
    || die "$id: manifest.json nennt eine andere Kennung oder Version als die Richtlinie"
  act mkdir -p "$PLUGINS/$id"
  for asset in main.js manifest.json styles.css; do
    if [[ -f "$tmp/$asset" ]]; then act cp "$tmp/$asset" "$PLUGINS/$id/$asset"; else act rm -f "$PLUGINS/$id/$asset"; fi
  done
}

remove_unlisted() {
  local dir id
  ((KEEP_UNLISTED)) && return 0
  [[ -d "$PLUGINS" ]] || return 0
  for dir in "$PLUGINS"/*/; do
    [[ -d "$dir" ]] || continue
    id=$(basename "$dir")
    if ! jq -e --arg id "$id" 'any(.plugins[]; .id == $id)' "$POLICY_EFFECTIVE" >/dev/null; then
      log "$id: nicht in der Richtlinie, wird entfernt"
      act rm -rf "$dir"
    fi
  done
}

write_community_plugins() {
  jq '[.plugins[].id]' "$POLICY_EFFECTIVE" | write_file "$OBS/community-plugins.json"
}

# Server, Anmeldedienst und Client des StoneIntelligence-Plugins vorbelegen; Tokens und alles andere bleibt unangetastet.
preconfigure_plugin() {
  local data="$PLUGINS/stoneintelligence/data.json" current='{}'
  [[ -f "$data" ]] && current=$(<"$data")
  jq -e 'type == "object"' <<<"$current" >/dev/null 2>&1 || current='{}'
  jq --argjson s "$(jq '.server' "$POLICY_EFFECTIVE")" \
     '. + {platformApiUrl: $s.url, oidcIssuerUrl: $s.oidcIssuer, oidcClientId: $s.oidcClientId}' <<<"$current" | write_file "$data"
}

# Plugins sollen ihre Einstellungen speichern koennen: data.json gehoert der Person, alles andere root.
ensure_data_files() {
  local dir
  for dir in "$PLUGINS"/*/; do
    [[ -d "$dir" ]] || continue
    [[ -e "${dir%/}/data.json" || "$(jq -r 'if .precreateData == null then true else .precreateData end' "$POLICY")" != true ]] || echo '{}' | write_file "${dir%/}/data.json"
  done
}

apply_ownership() {
  local dir data
  act mkdir -p "$VAULT" "$OBS" "$PLUGINS"
  act chown "$OWNER:$OWNER_GROUP" "$VAULT"
  # .obsidian: root, aber mit Sticky-Bit und fuer alle beschreibbar. Obsidian legt darin weiter eigene Dateien an
  # (app.json, workspace.json), doch plugins/ und community-plugins.json lassen sich weder ersetzen noch umbenennen.
  act chown "root:$ROOT_GROUP" "$OBS"
  act chmod 1777 "$OBS"
  act chown -R "root:$ROOT_GROUP" "$PLUGINS"
  act find "$PLUGINS" -type d -exec chmod 755 {} +
  act find "$PLUGINS" -type f -exec chmod 644 {} +
  for dir in "$PLUGINS"/*/; do
    data="${dir%/}/data.json"
    [[ -f "$data" ]] && act chown "$OWNER:$OWNER_GROUP" "$data"
  done
  if [[ -e "$OBS/community-plugins.json" ]]; then
    act chown "root:$ROOT_GROUP" "$OBS/community-plugins.json"
    act chmod 644 "$OBS/community-plugins.json"
  fi
  # macOS: zusaetzlich unveraenderlich markieren (data.json ausgenommen)
  if [[ "$OS" == macos && "$(jq -r '.immutable // false' "$POLICY")" == true ]]; then
    act find "$PLUGINS" -not -name data.json -exec chflags uchg {} +
    act chflags uchg "$OBS/community-plugins.json"
  fi
}

# Obsidians eigene Verwaltungsrichtlinie (ab 1.15; gilt nur in der Work-Ausgabe, siehe ADR 0019): Plugins nur in
# den festgelegten Versionen, kein Entwicklerwerkzeug. Sie liegt ausserhalb des Benutzerprofils.
write_native_policy() {
  [[ "$(jq -r 'if .nativePolicy.enabled == null then true else .nativePolicy.enabled end' "$POLICY")" == true ]] || return 0
  local target; target=$(native_policy_path)
  act mkdir -p "$(dirname "$target")"
  jq '{
        schemaVersion: 1, plugins: false, corePlugins: [],
        communityPlugins: [.plugins[] | {id: .id, version: .version, enable: "manual"}],
        privatePlugins: [],
        themes: (.nativePolicy.themes // false), snippets: (.nativePolicy.snippets // false),
        devTools: (.nativePolicy.devTools // false), insider: false
      }' "$POLICY_EFFECTIVE" | write_file "$target"
  act chmod 644 "$target"
}

main() {
  while (($#)); do
    case "$1" in
      --policy) POLICY="${2:?--policy braucht eine Datei}"; shift 2 ;;
      --dry-run) DRY_RUN=1; shift ;;
      --keep-unlisted) KEEP_UNLISTED=1; shift ;;
      -h | --help) usage; exit 0 ;;
      *) usage >&2; die "unbekanntes Argument: $1" ;;
    esac
  done
  [[ -r "$POLICY" ]] || { usage >&2; die "Richtliniendatei fehlt oder ist nicht lesbar"; }
  for tool in jq curl; do command -v "$tool" >/dev/null || die "$tool fehlt"; done
  ((EUID == 0)) || ((DRY_RUN)) || [[ -n "${MANAGED_OBSIDIAN_ALLOW_NON_ROOT:-}" ]] || die "bitte als root ausfuehren"

  OS=$(os_name)
  WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
  validate_policy
  resolve_vault
  resolve_owner
  apply_server_policy

  unlock
  if ((SERVER_POLICY_OK)); then
    act mkdir -p "$PLUGINS"
    local count i; count=$(jq '.plugins | length' "$POLICY_EFFECTIVE")
    for ((i = 0; i < count; i++)); do install_plugin "$i"; done
    remove_unlisted
    write_community_plugins
    preconfigure_plugin
    ensure_data_files
    write_native_policy
  fi
  apply_ownership
  log "fertig"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main "$@"; fi
