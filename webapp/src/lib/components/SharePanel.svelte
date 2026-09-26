<script lang="ts">
  import { onMount } from "svelte";
  import { api, ApiError, type AccessReport, type Group, type ScopeType, type Vault } from "../api";
  import { describeScope, describeSource, explainAccessError, permissionsFor, permissionsLabel, PRESETS, presetOf, type PresetId } from "../accessPlan";

  // Freigabe einer Notiz/Datei oder eines Ordners (ADR 0011) - dasselbe wie "Freigabe…" in Obsidian.
  type Target = { kind: "entry"; noteId: string; path: string } | { kind: "folder"; path: string };
  let { vault, target, onClose }: { vault: Vault; target: Target; onClose: () => void } = $props();

  let report = $state<AccessReport | null>(null);
  let groups = $state<Group[]>([]);
  let busy = $state(false);
  let error = $state("");
  let message = $state("");
  let addWho = $state("EVERYONE:");
  let addPreset = $state<PresetId>("read");

  const mayManage = $derived(report?.mine.permissions.includes("MANAGE") ?? false);
  const otherGrants = $derived(report?.grants.filter(g => g.scopeType !== "USER") ?? []);
  const title = $derived(target.kind === "folder" ? (target.path ? `Ordner „${target.path}“` : "Ganzer Vault") : target.path.split("/").pop());

  function explain(e: unknown) { return e instanceof ApiError ? explainAccessError(e.status) : (e as Error).message; }
  async function reload() {
    report = target.kind === "entry" ? await api.noteAccess(vault.id, target.noteId) : await api.folderAccess(vault.id, target.path);
  }
  onMount(async () => {
    try { [, groups] = await Promise.all([reload(), api.listGroups(vault.id)]); }
    catch (e) { error = explain(e); }
  });

  async function run(action: () => Promise<unknown>, done: string) {
    if (busy) return;
    busy = true; error = ""; message = "";
    try { await action(); await reload(); message = done; }
    catch (e) { error = explain(e); }
    finally { busy = false; }
  }
  function put(scopeType: ScopeType, subject: string | null, preset: PresetId, label: string) {
    const change = { scopeType, subject, permissions: permissionsFor(preset) };
    return run(() => target.kind === "entry" ? api.putNoteGrant(vault.id, target.noteId, change) : api.putFolderGrant(vault.id, target.path, change),
      `${label}: ${PRESETS.find(p => p.id === preset)?.label}.`);
  }
  function remove(scopeType: ScopeType, subject: string | null, label: string) {
    return run(() => target.kind === "entry" ? api.removeNoteGrant(vault.id, target.noteId, scopeType, subject) : api.removeFolderGrant(vault.id, target.path, scopeType, subject),
      `${label}: wieder wie darüber festgelegt.`);
  }
  function addChosen() {
    const [scopeType, subject] = addWho.split(":") as [ScopeType, string];
    const label = scopeType === "EVERYONE" ? "Alle Mitglieder" : `Gruppe „${groups.find(g => g.id === subject)?.name}“`;
    void put(scopeType, subject || null, addPreset, label);
  }
</script>

<section class="share-panel" aria-label="Freigabe">
  <header><h3>Freigabe: {title}</h3><button class="quiet" onclick={onClose}>Schließen</button></header>
  {#if !report && !error}<p class="hint" role="status">Wird geladen…</p>{/if}
  {#if report}
    <p class="mine">Deine Rechte hier: <strong>{permissionsLabel(report.mine.permissions)}</strong> · {describeSource(report.mine.source)}</p>
    {#if !mayManage}<p class="hint">Freigaben ändern kann, wer diese Stelle verwaltet.</p>
    {:else}
      <h4>Wer hat Zugriff</h4>
      <ul class="rows">
        {#each report.members as member (member.subject)}
          {@const own = report.grants.find(g => g.scopeType === "USER" && g.subject === member.subject)}
          <li>
            <div><span class="name">{member.subject}</span><span class="detail">{permissionsLabel(member.permissions)} · {describeSource(member.source)}{member.groups.length ? ` · ${member.groups.join(", ")}` : ""}</span></div>
            <select aria-label={`Rechte für ${member.subject}`} disabled={busy} value="" onchange={e => { const v = (e.currentTarget as HTMLSelectElement).value; if (v) void put("USER", member.subject, v as PresetId, member.subject); }}>
              <option value="">{own ? (presetOf(own.permissions) === "custom" ? `Eigene Auswahl: ${permissionsLabel(own.permissions)}` : `Hier: ${PRESETS.find(p => p.id === presetOf(own.permissions))?.label}`) : "Ändern…"}</option>
              {#each PRESETS as preset}<option value={preset.id}>{preset.label}</option>{/each}
            </select>
            {#if own}<button class="quiet" disabled={busy} onclick={() => remove("USER", member.subject, member.subject)}>Zurücksetzen</button>{/if}
          </li>
        {/each}
      </ul>
      {#if otherGrants.length}
        <h4>Freigaben hier</h4>
        <ul class="rows">{#each otherGrants as grant (grant.id)}<li><div><span class="name">{describeScope(grant)}</span><span class="detail">{permissionsLabel(grant.permissions)}</span></div><button class="quiet" disabled={busy} onclick={() => remove(grant.scopeType, grant.subject, describeScope(grant))}>Entfernen</button></li>{/each}</ul>
      {/if}
      {#if report.inherited.length}
        <h4>Von weiter oben</h4>
        <ul class="rows">{#each report.inherited as grant (grant.id)}<li><div><span class="name">{describeScope(grant)}</span><span class="detail">{permissionsLabel(grant.permissions)} · {describeSource(grant)}</span></div></li>{/each}</ul>
      {/if}
      <form class="add" onsubmit={e => { e.preventDefault(); addChosen(); }}>
        <label>Für<select bind:value={addWho} disabled={busy}><option value="EVERYONE:">Alle Mitglieder</option>{#each groups as group}<option value={`GROUP:${group.id}`}>Gruppe „{group.name}“</option>{/each}</select></label>
        <label>Rechte<select bind:value={addPreset} disabled={busy}>{#each PRESETS as preset}<option value={preset.id}>{preset.label}</option>{/each}</select></label>
        <button class="primary" disabled={busy}>Übernehmen</button>
      </form>
    {/if}
  {/if}
  {#if message}<p class="feedback" role="status">{message}</p>{/if}
  {#if error}<p class="feedback error" role="alert">{error}</p>{/if}
</section>

<style>
  .share-panel { background: var(--surface); border: 1px solid var(--line); border-radius: 6px; padding: 1.1rem 1.25rem; margin-bottom: 1.25rem; }
  header { display: flex; justify-content: space-between; align-items: center; gap: 1rem; } h3 { font-size: 1.05rem; margin: 0; overflow-wrap: anywhere; }
  h4 { font-size: .8rem; text-transform: uppercase; letter-spacing: .06em; color: var(--ink-dim); margin: 1.25rem 0 .4rem; }
  .mine { margin: .8rem 0 0; } .rows { list-style: none; margin: 0; padding: 0; }
  .rows li { display: flex; align-items: center; gap: .75rem; padding: .6rem 0; border-top: 1px solid var(--line); } .rows li > div { flex: 1; min-width: 0; }
  .name { display: block; font-weight: 500; overflow-wrap: anywhere; } .detail { display: block; font-size: .78rem; color: var(--ink-dim); }
  .rows select { max-width: 15rem; } .add { display: flex; flex-wrap: wrap; align-items: end; gap: .75rem; margin-top: 1rem; }
  .add label { font-size: .82rem; font-weight: 500; display: flex; flex-direction: column; gap: .3rem; } .feedback { margin: .8rem 0 0; font-size: .88rem; } .error { color: var(--rust); }
  @media (max-width: 700px) { .rows li { flex-wrap: wrap; } .rows select { max-width: none; width: 100%; } }
</style>
