#!/usr/bin/env bash
# Parst managed-obsidian.ps1 mit dem PowerShell-Parser (ohne es auszufuehren). Ohne pwsh wird uebersprungen:
# das Skript ist dann nur gelesen, nicht geprueft.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if ! command -v pwsh >/dev/null 2>&1; then
  echo "parse-powershell: uebersprungen (pwsh nicht installiert)"; exit 0
fi
pwsh -NoProfile -Command '
  $errors = $null
  [System.Management.Automation.Language.Parser]::ParseFile($args[0], [ref]$null, [ref]$errors) | Out-Null
  if ($errors) { $errors | ForEach-Object { Write-Error $_.ToString() }; exit 1 }
  Write-Output "parse-powershell: managed-obsidian.ps1 ist syntaktisch gueltig"
' "$HERE/../managed-obsidian.ps1"
