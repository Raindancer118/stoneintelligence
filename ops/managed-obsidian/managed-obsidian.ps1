<#
.SYNOPSIS
  Verwaltetes Obsidian (Windows): Firmen-Vault anlegen, genau die erlaubten Plugins installieren, Rechte setzen.
.DESCRIPTION
  Als Administrator per MDM (Intune) oder Aufgabenplanung ausfuehren, gern wiederkehrend zur Selbstheilung.
  Mehrfaches Ausfuehren ist harmlos. Entwurf und Grenzen: docs/adr/0019-plugin-steuerung.md.
  Auf einem echten Windows-Rechner noch nicht erprobt (die Entwicklung lief unter Linux); die Rechte-Logik entspricht
  dem Bash-Skript, die Umsetzung mit icacls ist unverifiziert.
.PARAMETER Policy
  Richtliniendatei (JSON, Vorlage: policy.example.json).
.PARAMETER DryRun
  Nur anzeigen, nichts veraendern.
.PARAMETER KeepUnlisted
  Plugin-Ordner, die nicht in der Richtlinie stehen, nicht entfernen.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Policy,
  [switch]$DryRun,
  [switch]$KeepUnlisted
)
$ErrorActionPreference = 'Stop'

$GithubBase = if ($env:MANAGED_OBSIDIAN_GITHUB_BASE) { $env:MANAGED_OBSIDIAN_GITHUB_BASE } else { 'https://github.com' }
# Bekannte SIDs, damit die Rechte auch auf nicht-englischen Windows-Versionen stimmen.
$Sids = @{ System = '*S-1-5-18'; Administrators = '*S-1-5-32-544'; Users = '*S-1-5-32-545' }
$Utf8 = New-Object System.Text.UTF8Encoding $false

function Write-Log([string]$Message) { Write-Output "managed-obsidian: $Message" }
function Stop-Run([string]$Message) { throw "managed-obsidian: Fehler: $Message" }

# Jede Aenderung am Dateisystem laeuft hier durch; -DryRun zeigt sie nur an.
function Invoke-Change([string]$Description, [scriptblock]$Action) {
  if ($DryRun) { Write-Log "[dry-run] $Description" } else { & $Action }
}

function Write-TextFile([string]$Path, [string]$Text) {
  Invoke-Change "schreibe $Path" { [System.IO.File]::WriteAllText($Path, $Text, $Utf8) }
}

function Get-Sha256([string]$Path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant() }

function Test-Policy($P) {
  if ($P.schemaVersion -ne 1) { Stop-Run 'Richtlinie: schemaVersion 1 erwartet' }
  if (-not ($P.server.url -and $P.server.oidcIssuer -and $P.server.oidcClientId)) { Stop-Run 'Richtlinie: server.url, server.oidcIssuer und server.oidcClientId fehlen' }
  if (-not ($P.plugins | Where-Object { $_.id -eq 'stoneintelligence' })) { Stop-Run 'Richtlinie: plugins muss das Plugin "stoneintelligence" enthalten' }
  foreach ($p in $P.plugins) {
    if ($p.id -notmatch '^[A-Za-z0-9._-]+$' -or $p.repo -notmatch '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$' -or $p.version -notmatch '^[A-Za-z0-9._+-]+$' `
        -or $p.sha256.'main.js' -notmatch '^[0-9a-f]{64}$' -or $p.sha256.'manifest.json' -notmatch '^[0-9a-f]{64}$') {
      Stop-Run 'Richtlinie: jedes Plugin braucht id, repo (Besitzer/Name), version und sha256 fuer main.js und manifest.json'
    }
  }
}

# Optional: der Server sagt, welche Plugins aus der Richtlinie erlaubt sind (ADR 0019). Ohne Antwort aendert der Lauf
# den Plugin-Bestand nicht, repariert aber weiter Rechte und Dateien.
function Resolve-ServerPolicy($P) {
  if (-not $P.serverPolicy) { return @{ Policy = $P; Ok = $true } }
  try {
    $token = (Get-Content -LiteralPath $P.serverPolicy.tokenFile -Raw).Trim()
    $uri = '{0}/api/v1/vaults/{1}/plugin-policy' -f $P.server.url.TrimEnd('/'), $P.serverPolicy.vaultId
    $server = Invoke-RestMethod -Uri $uri -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 20
    if (-not $server.mode) { throw 'keine Richtlinie in der Antwort' }
  } catch {
    Write-Log 'Warnung: Richtlinie des Servers nicht erreichbar, der Plugin-Bestand bleibt unveraendert'
    return @{ Policy = $P; Ok = $false }
  }
  if ($server.mode -eq 'ENFORCE') {
    $P.plugins = @($P.plugins | Where-Object {
      $plugin = $_
      $plugin.id -eq 'stoneintelligence' -or ($server.allowed | Where-Object { $_.id -eq $plugin.id -and (-not $_.version -or $_.version -eq $plugin.version) })
    })
  }
  return @{ Policy = $P; Ok = $true }
}

function Test-PluginCurrent($Plugin, [string]$Dir) {
  if (-not (Test-Path -LiteralPath $Dir -PathType Container)) { return $false }
  foreach ($asset in 'main.js', 'manifest.json', 'styles.css') {
    $expected = $Plugin.sha256.$asset
    $file = Join-Path $Dir $asset
    if ($expected) {
      if (-not (Test-Path -LiteralPath $file) -or (Get-Sha256 $file) -ne $expected) { return $false }
    } elseif (Test-Path -LiteralPath $file) { return $false }
  }
  return $true
}

function Install-Plugin($Plugin, [string]$PluginsDir, [string]$Work) {
  $dir = Join-Path $PluginsDir $Plugin.id
  if (Test-PluginCurrent $Plugin $dir) { Write-Log "$($Plugin.id) $($Plugin.version): unveraendert"; return }
  Write-Log "$($Plugin.id) $($Plugin.version): installiere"
  $tag = if ($Plugin.tag) { $Plugin.tag } else { $Plugin.version }
  $tmp = Join-Path $Work $Plugin.id
  New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  foreach ($asset in 'main.js', 'manifest.json', 'styles.css') {
    $expected = $Plugin.sha256.$asset
    if (-not $expected) { continue }
    $target = Join-Path $tmp $asset
    try { Invoke-WebRequest -UseBasicParsing -Uri "$GithubBase/$($Plugin.repo)/releases/download/$tag/$asset" -OutFile $target -TimeoutSec 120 }
    catch { Stop-Run "$($Plugin.id): $asset nicht ladbar ($($Plugin.repo) $tag)" }
    $got = Get-Sha256 $target
    if ($got -ne $expected) { Stop-Run "$($Plugin.id): Pruefsumme von $asset stimmt nicht (erwartet $expected, erhalten $got) - nichts installiert" }
  }
  $manifest = Get-Content -LiteralPath (Join-Path $tmp 'manifest.json') -Raw | ConvertFrom-Json
  if ($manifest.id -ne $Plugin.id -or $manifest.version -ne $Plugin.version) { Stop-Run "$($Plugin.id): manifest.json nennt eine andere Kennung oder Version als die Richtlinie" }
  Invoke-Change "installiere $($Plugin.id) nach $dir" {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    foreach ($asset in 'main.js', 'manifest.json', 'styles.css') {
      $source = Join-Path $tmp $asset
      if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $dir $asset) -Force }
      else { Remove-Item -LiteralPath (Join-Path $dir $asset) -Force -ErrorAction SilentlyContinue }
    }
  }
}

function Set-Permissions([string]$Vault, [string]$Obs, [string]$PluginsDir, [string]$Owner) {
  # Alles unter plugins\ und community-plugins.json: Administratoren und SYSTEM voll, Benutzer nur lesen und ausfuehren.
  Invoke-Change "Rechte fuer $PluginsDir" {
    icacls $PluginsDir /inheritance:r /grant:r "$($Sids.System):(OI)(CI)F" "$($Sids.Administrators):(OI)(CI)F" "$($Sids.Users):(OI)(CI)RX" /T /C /Q | Out-Null
    icacls $PluginsDir /setowner $Sids.Administrators /T /C /Q | Out-Null
    # Die Einstellungen der Plugins (data.json) soll die Person weiter speichern koennen.
    Get-ChildItem -LiteralPath $PluginsDir -Recurse -Filter data.json -File | ForEach-Object {
      icacls $_.FullName /grant "${Owner}:(M)" /C /Q | Out-Null
    }
  }
  $communityFile = Join-Path $Obs 'community-plugins.json'
  if (Test-Path -LiteralPath $communityFile) {
    Invoke-Change "Rechte fuer $communityFile" {
      icacls $communityFile /inheritance:r /grant:r "$($Sids.System):F" "$($Sids.Administrators):F" "$($Sids.Users):RX" /C /Q | Out-Null
      icacls $communityFile /setowner $Sids.Administrators /C /Q | Out-Null
    }
  }
  # .obsidian: Benutzer duerfen weiter eigene Dateien anlegen, aber keine Eintraege entfernen oder umbenennen, deren
  # Rechte sie nicht selbst haben - so laesst sich plugins\ nicht wegschieben (Gegenstueck zum Sticky-Bit unter Linux).
  Invoke-Change "Rechte fuer $Obs" {
    icacls $Obs /deny "$($Sids.Users):(DC)" /C /Q | Out-Null
  }
}

function Resolve-Owner($P) {
  if ($P.owner) { return $P.owner }
  $user = (Get-CimInstance Win32_ComputerSystem).UserName
  if (-not $user) { Stop-Run 'Richtlinie: owner (Benutzer, der im Vault arbeitet) fehlt' }
  return $user
}

# Obsidians eigene Verwaltungsrichtlinie (ab 1.15; gilt nur in der Work-Ausgabe, siehe ADR 0019).
function Write-NativePolicy($P) {
  $enabled = if ($null -eq $P.nativePolicy -or $null -eq $P.nativePolicy.enabled) { $true } else { [bool]$P.nativePolicy.enabled }
  if (-not $enabled) { return }
  $target = if ($env:MANAGED_OBSIDIAN_NATIVE_POLICY) { $env:MANAGED_OBSIDIAN_NATIVE_POLICY } else { 'C:\Program Files\Obsidian\policy.json' }
  function Flag($Name) { if ($null -ne $P.nativePolicy -and $null -ne $P.nativePolicy.$Name) { [bool]$P.nativePolicy.$Name } else { $false } }
  $native = [ordered]@{
    schemaVersion = 1; plugins = $false; corePlugins = @()
    communityPlugins = @($P.plugins | ForEach-Object { [ordered]@{ id = $_.id; version = $_.version; enable = 'manual' } })
    privatePlugins = @(); themes = (Flag 'themes'); snippets = (Flag 'snippets'); devTools = (Flag 'devTools'); insider = $false
  }
  Invoke-Change "Verzeichnis fuer $target" { New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null }
  Write-TextFile $target ($native | ConvertTo-Json -Depth 10)
}

# --- Ablauf -----------------------------------------------------------------------------------------------------

if (-not (Test-Path -LiteralPath $Policy)) { Stop-Run 'Richtliniendatei fehlt oder ist nicht lesbar' }
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $DryRun -and -not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { Stop-Run 'bitte als Administrator ausfuehren' }

$policyData = Get-Content -LiteralPath $Policy -Raw | ConvertFrom-Json
Test-Policy $policyData
$vault = $policyData.vaultPath
if ($vault -isnot [string]) { $vault = $vault.windows }
if (-not $vault -or $vault -notmatch '^[A-Za-z]:\\') { Stop-Run 'Richtlinie: vaultPath fehlt oder ist nicht absolut (fuer windows)' }
$obs = Join-Path $vault '.obsidian'
$pluginsDir = Join-Path $obs 'plugins'
$owner = Resolve-Owner $policyData
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("managed-obsidian-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $work | Out-Null

try {
  $resolved = Resolve-ServerPolicy $policyData
  $effective = $resolved.Policy
  Invoke-Change "lege $pluginsDir an" { New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null }

  if ($resolved.Ok) {
    foreach ($plugin in $effective.plugins) { Install-Plugin $plugin $pluginsDir $work }
    if (-not $KeepUnlisted -and (Test-Path -LiteralPath $pluginsDir)) {
      $ids = @($effective.plugins | ForEach-Object { $_.id })
      Get-ChildItem -LiteralPath $pluginsDir -Directory | Where-Object { $ids -notcontains $_.Name } | ForEach-Object {
        Write-Log "$($_.Name): nicht in der Richtlinie, wird entfernt"
        $path = $_.FullName
        Invoke-Change "entferne $path" { Remove-Item -LiteralPath $path -Recurse -Force }
      }
    }
    Write-TextFile (Join-Path $obs 'community-plugins.json') (ConvertTo-Json -InputObject @($effective.plugins | ForEach-Object { $_.id }))

    # Server, Anmeldedienst und Client vorbelegen; Tokens und alles andere bleibt unangetastet.
    $dataPath = Join-Path (Join-Path $pluginsDir 'stoneintelligence') 'data.json'
    $data = if (Test-Path -LiteralPath $dataPath) { Get-Content -LiteralPath $dataPath -Raw | ConvertFrom-Json } else { [pscustomobject]@{} }
    if ($data -isnot [pscustomobject]) { $data = [pscustomobject]@{} }
    $data | Add-Member -NotePropertyName platformApiUrl -NotePropertyValue $effective.server.url -Force
    $data | Add-Member -NotePropertyName oidcIssuerUrl -NotePropertyValue $effective.server.oidcIssuer -Force
    $data | Add-Member -NotePropertyName oidcClientId -NotePropertyValue $effective.server.oidcClientId -Force
    Write-TextFile $dataPath ($data | ConvertTo-Json -Depth 20)

    $precreate = if ($null -eq $effective.precreateData) { $true } else { [bool]$effective.precreateData }
    if ($precreate -and (Test-Path -LiteralPath $pluginsDir)) {
      Get-ChildItem -LiteralPath $pluginsDir -Directory | ForEach-Object {
        $file = Join-Path $_.FullName 'data.json'
        if (-not (Test-Path -LiteralPath $file)) { Write-TextFile $file '{}' }
      }
    }
    Write-NativePolicy $effective
  }
  Set-Permissions $vault $obs $pluginsDir $owner
  Write-Log 'fertig'
} finally {
  Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
