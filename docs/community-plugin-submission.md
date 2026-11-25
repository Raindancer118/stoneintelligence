# Obsidian community directory: submission prep

Status: prepared, NOT submitted. Checked against the Obsidian docs on 2026-11-25
(Developer policies, Submission requirements for plugins, Plugin guidelines, Submit your plugin, Manifest).

## The process changed: no PR to obsidian-releases

`obsidianmd/obsidian-releases` no longer has a plugin PR template (`?template=plugin.md` returns 404) and
no longer needs a `community-plugins.json` edit from us. Submission goes through
<https://community.obsidian.md>. The entry the directory derives from our repo looks like this
(for reference only, do not open a PR with it):

```json
{
  "id": "stoneintelligence",
  "name": "StoneIntelligence",
  "author": "Raindancer118",
  "description": "Sync vaults live between people, set access policies per note, and ingest knowledge into a shared index with optional AI processing.",
  "repo": "Raindancer118/stoneintelligence"
}
```

## Submission form (community.obsidian.md, Plugins, New plugin)

- GitHub repository URL: `https://github.com/Raindancer118/stoneintelligence`
- Owner: yourself (or an organization)
- Agree to the Developer policies; confirm you will keep supporting the plugin.

## Checklist

Requirements and where we stand:

| Requirement | Status |
| :--- | :--- |
| README.md, LICENSE, manifest.json in repo root | README ok; root `manifest.json` added (kept in sync with `plugin/manifest.json` by a test); license file is `LICENSE.md` (see decision 1) |
| `id` lowercase letters/hyphens, no `obsidian`, not ending in `plugin` | ok (`stoneintelligence`) |
| `name` without "Obsidian"/"Plugin", Basic Latin | ok |
| Description <= 250 chars, ends with `.`, action statement, no emoji | fixed (was German, no action verb) |
| `minAppVersion` appropriate | 1.6.6, ok |
| `authorUrl` / `fundingUrl` | authorUrl added; no fundingUrl (no donations) |
| `isDesktopOnly` vs Node/Electron | see decision 2 |
| Command IDs without plugin id, no plugin name in command names, no default hotkeys | fixed (ids were `stoneintelligence-*`) |
| Only errors in console | fixed (debug/warn removed; opt-in `debugLog`) |
| No `innerHTML`/`outerHTML`/`insertAdjacentHTML` | ok |
| Settings headings via `setHeading()`, no "settings" in headings | ok |
| Network via `requestUrl` | ok for HTTP; WebSocket for live sync is native (required, disclosed) |
| No telemetry, ads, self-update, obfuscation | ok |
| Disclosures in README: account, network, AI, files outside vault, closed source | added (DE/EN) |
| GitHub release tag == manifest version, assets main.js/manifest.json/styles.css | `plugin-release.yml` does this on tag push |
| Sample code removed | ok |

Guarded by `plugin/test/submissionRules.test.ts`.

## Decisions for the owner

1. **License (possible blocker, case by case).** The Developer policies require "a LICENSE file ... and
   clearly indicate the license". `LICENSE.md` states a proprietary license ("Alle Rechte vorbehalten",
   private non-commercial use of official releases only, no copying, redistribution, own instance or
   integration without permission). There is no rule that demands an open-source license, and "Close sourced
   code" is only a README disclosure item that is "handled on a case by case basis". The risk is the reviewers'
   judgment, and the license sits awkwardly with the README's "Eigene Instanz betreiben" section. Options:
   (a) submit as is and accept a possible rejection; (b) keep the server proprietary but license `plugin/`
   under an open license (e.g. MIT/GPL) with its own LICENSE in the root or a clear scope statement;
   (c) stay on BRAT. The license was not changed.
2. **`isDesktopOnly: false` with Node/Electron.** `desktopVaults.ts` and `desktopAuthRedirect.ts` use
   `require("electron" | "fs" | "path" | "http" | "@electron/remote")`, only on desktop and behind
   try/catch or `Platform.isDesktopApp`. Mobile is supported, so `true` would drop it. The automated review
   may flag those `require`s anyway. Options: keep and justify if flagged, or set `true`.
3. **"No payment required" claim** in the README is stated for the hosted instance; confirm it is true
   (there is a quota for AI uploads).

## Manual steps

1. Decide 1 and 2; confirm 3.
2. Optionally rename `LICENSE.md` to `LICENSE` (GitHub's directory docs ask for a file named LICENSE).
3. Bump version in `plugin/manifest.json` AND root `manifest.json` (the test enforces equality), commit, push.
4. `git tag X.Y.Z && git push origin X.Y.Z` so a release with `main.js`, `manifest.json`, `styles.css` exists
   (tag must equal the manifest version; the workflow stamps it).
5. Sign in at community.obsidian.md with an Obsidian account, connect GitHub, add the plugin (form above).
6. Fix whatever the automated review reports, release a new version, then announce in the forum
   (Share & showcase) and Discord `#updates`.
