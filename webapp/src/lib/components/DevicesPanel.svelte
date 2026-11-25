<script lang="ts">
  import { onMount } from "svelte";
  import Skeleton from "./Skeleton.svelte";
  import { api, ApiError, type Device, type PluginPolicy } from "../api";
  import { explainAccessError } from "../accessPlan";
  import { deviceStatusText, deviceTone, formatAllowedLines, parseAllowedLines } from "../devicesText";
  import { formatDate } from "../historyText";

  // Plugin-Richtlinie und Geräte (ADR 0019) – dieselben Funktionen wie in Obsidian (Vault-Verwaltung → Geräte und Plugins).
  let { vault }: { vault: { id: string; name: string } } = $props();

  let policy = $state<PluginPolicy | null>(null);
  let devices = $state<Device[]>([]);
  let loading = $state(true);
  let mode = $state<"OFF" | "ENFORCE">("OFF");
  let list = $state("");
  let busy = $state(false);
  let message = $state("");
  let error = $state("");

  function explain(e: unknown) { return e instanceof ApiError && e.status !== 422 ? explainAccessError(e.status) : (e as Error).message; }

  async function load() {
    error = "";
    try {
      const [loaded, found] = await Promise.all([api.pluginPolicy(vault.id), api.listDevices(vault.id)]);
      policy = loaded; devices = found; mode = loaded.mode; list = formatAllowedLines(loaded.allowed);
    } catch (e) { error = explain(e); }
    finally { loading = false; }
  }
  onMount(() => { void load(); });

  async function run(action: () => Promise<unknown>, done: string) {
    if (busy) return;
    busy = true; error = ""; message = "";
    try { await action(); message = done; await load(); }
    catch (e) { error = explain(e); }
    finally { busy = false; }
  }

  function save() {
    const parsed = parseAllowedLines(list);
    if (parsed.error) { error = parsed.error; return; }
    void run(() => api.setPluginPolicy(vault.id, { mode, allowed: parsed.rules }), mode === "ENFORCE" ? "Die Richtlinie wird durchgesetzt." : "Die Richtlinie ist aus.");
  }

  function revoke(device: Device) {
    if (!window.confirm(`„${device.name}“ von ${device.person} kann danach nicht mehr synchronisieren. Gerät sperren?`)) return;
    void run(() => api.revokeDevice(vault.id, device.deviceId), `„${device.name}“ ist gesperrt.`);
  }
  function wipe(device: Device) {
    if (!window.confirm(`Beim nächsten Verbinden löscht „${device.name}“ von ${device.person} seine lokale Kopie dieses Vaults endgültig und meldet sich ab. Das Gerät ist danach gesperrt. Daten auf dem Gerät löschen?`)) return;
    void run(() => api.wipeDevice(vault.id, device.deviceId), `„${device.name}“ löscht die Daten beim nächsten Verbinden.`);
  }
  function reinstate(device: Device) {
    void run(() => api.reinstateDevice(vault.id, device.deviceId), `„${device.name}“ ist wieder zugelassen.`);
  }
</script>

{#if loading}
  <Skeleton kind="list" label="Geräte und Plugins werden geladen…" />
{:else if policy}
  <section class="devices" aria-label="Plugin-Richtlinie">
    <h3>Plugin-Richtlinie</h3>
    <p class="hint">Mit „Durchsetzen“ dürfen sich nur noch Geräte verbinden, auf denen ausschließlich die freigegebenen Community-Plugins aktiv sind. Das StoneIntelligence-Plugin ist immer erlaubt. Die Prüfung läuft im Plugin und ist eine Leitplanke; ein hartes Schloss entsteht erst mit der Geräteverwaltung eurer IT.</p>
    <form class="policy-form" onsubmit={e => { e.preventDefault(); save(); }}>
      <label for="policy-mode">Richtlinie</label>
      <select id="policy-mode" bind:value={mode} disabled={busy}>
        <option value="OFF">Aus</option>
        <option value="ENFORCE">Durchsetzen</option>
      </select>
      <label for="policy-plugins">Freigegebene Plugins</label>
      <textarea id="policy-plugins" rows="6" bind:value={list} disabled={busy} spellcheck="false" placeholder="calendar@1.5.10"></textarea>
      <p class="hint">Eine Zeile je Plugin: die Kennung aus dessen manifest.json, mit @Version, wenn nur diese Version erlaubt sein soll.</p>
      <div><button class="primary" type="submit" disabled={busy}>Richtlinie speichern</button></div>
    </form>
  </section>

  <section class="devices" aria-label="Geräte">
    <h3>Geräte</h3>
    {#if devices.length === 0}
      <p class="hint">Noch kein Gerät hat sich gemeldet. Geräte erscheinen hier, sobald ihr Plugin sich verbindet.</p>
    {:else}
      <table>
        <tbody>
          {#each devices as device (device.deviceId)}
            <tr>
              <td class="label">{device.name}<span class="hint device-person">{device.person}</span></td>
              <td><span class={`device-state is-${deviceTone(device)}`}>{deviceStatusText(device)}</span>
                <span class="hint device-meta">{device.platform} · Obsidian {device.obsidianVersion} · zuletzt {formatDate(device.lastSeen)}</span></td>
              <td class="actions">
                {#if device.revoked || device.wipePending}
                  <button class="link" disabled={busy} onclick={() => reinstate(device)}>Wieder zulassen</button>
                {:else}
                  <button class="link" disabled={busy} onclick={() => revoke(device)}>Gerät sperren</button>
                  <button class="link" disabled={busy} onclick={() => wipe(device)}>Daten auf dem Gerät löschen</button>
                {/if}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    {/if}
  </section>
{/if}
{#if message}<p class="feedback" role="status">{message}</p>{/if}
{#if error}<div class="feedback error" role="alert"><p>{error}</p><button class="secondary" onclick={load}>Erneut versuchen</button></div>{/if}
