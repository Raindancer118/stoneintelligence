<script lang="ts">
  import { onMount } from "svelte";
  import { api, ApiError, type LinkingSettings, type Vault } from "../api";
  import { explainAccessError } from "../accessPlan";

  // Nächtliche Verlinkung (ADR 0012) – dieselben Schalter wie in Obsidian (Vault-Verwaltung → KI).
  let { vault, permissions, onStarted = () => {} }: { vault: Vault; permissions: string[]; onStarted?: () => void } = $props();

  let settings = $state<LinkingSettings | null>(null);
  let max = $state("");
  let busy = $state(false);
  let message = $state("");
  let error = $state("");
  const manage = $derived(permissions.includes("MANAGE"));
  const canRun = $derived(permissions.includes("WRITE"));

  function explain(e: unknown) { return e instanceof ApiError && e.status !== 422 ? explainAccessError(e.status) : (e as Error).message; }
  function when(iso: string) { return new Date(iso).toLocaleString("de-DE", { dateStyle: "medium", timeStyle: "short" }); }

  onMount(async () => {
    try { settings = await api.linkingSettings(vault.id); max = settings.maxLinksPerNote === null ? "" : String(settings.maxLinksPerNote); }
    catch (e) { error = explain(e); }
  });

  async function save(change: Partial<LinkingSettings>) {
    if (!settings || busy) return;
    const next = { ...settings, ...change };
    busy = true; error = ""; message = "";
    try {
      settings = await api.updateLinking(vault.id, { enabled: next.enabled, linkHumanNotes: next.linkHumanNotes, maxLinksPerNote: next.maxLinksPerNote, service: next.service });
      message = settings.enabled ? "Die Verlinkung läuft jetzt jede Nacht um 2 Uhr." : "Die nächtliche Verlinkung ist aus.";
    } catch (e) { error = explain(e); }
    finally { busy = false; }
  }

  function saveMax() {
    const parsed = max.trim() === "" ? null : Number.parseInt(max, 10);
    if (parsed !== null && (!Number.isFinite(parsed) || parsed < 1)) { error = "Bitte eine Zahl ab 1 eintragen oder das Feld leer lassen."; return; }
    void save({ maxLinksPerNote: parsed });
  }

  async function runNow() {
    busy = true; error = ""; message = "";
    try { await api.runLinking(vault.id); message = "Die Verlinkung läuft – den Fortschritt siehst du unter „Verarbeitung“."; onStarted(); }
    catch (e) { error = explain(e); }
    finally { busy = false; }
  }
</script>

{#if settings}
  <section class="block linking" aria-label="Verlinkung">
    <h3>Verlinkung</h3>
    <p class="hint">Nennt eine Notiz den Titel oder einen Alias einer anderen, wird die Stelle zum Link – nur eingefügtes Markup, der Text bleibt, wie er ist. Das geschieht auf dem Server, ohne externe KI; jeder Lauf lässt sich unten unter „Änderungen der KI“ rückgängig machen.</p>
    <label class="toggle"><input type="checkbox" checked={settings.enabled} disabled={!manage || busy} onchange={e => save({ enabled: e.currentTarget.checked })} /> Jede Nacht um 2 Uhr verlinken{settings.enabled && settings.requestedBy ? ` (mit den Rechten von ${settings.requestedBy})` : ""}</label>
    <label class="toggle"><input type="checkbox" checked={settings.linkHumanNotes} disabled={!manage || busy} onchange={e => save({ linkHumanNotes: e.currentTarget.checked })} /> Auch in Notizen von Menschen</label>
    <form class="max" onsubmit={e => { e.preventDefault(); saveMax(); }}>
      <label for="linking-max">Höchstens neue Links je Notiz und Lauf</label>
      <input id="linking-max" inputmode="numeric" placeholder="unbegrenzt" bind:value={max} disabled={!manage || busy} />
      <button class="secondary" disabled={!manage || busy}>Speichern</button>
    </form>
    <div class="run">
      <button class="primary" disabled={!canRun || busy} onclick={runNow}>Jetzt verlinken</button>
      <span class="hint">{settings.lastRunAt ? `Zuletzt: ${when(settings.lastRunAt)}` : "Noch nie gelaufen."}</span>
    </div>
    {#if message}<p class="feedback" role="status">{message}</p>{/if}
  </section>
{/if}
{#if error}<p class="feedback error" role="alert">{error}</p>{/if}

<style>
  .linking .toggle { display: flex; align-items: center; gap: .5rem; margin: .5rem 0; }
  .linking .toggle input { min-height: auto; }
  .max { display: flex; flex-wrap: wrap; align-items: end; gap: .6rem; margin: .8rem 0; } .max label { width: 100%; font-size: .82rem; font-weight: 500; } .max input { width: 8rem; }
  .run { display: flex; align-items: center; gap: .8rem; flex-wrap: wrap; margin-top: .8rem; }
  .feedback { margin: .8rem 0 0; font-size: .88rem; } .error { color: var(--rust); }
</style>
