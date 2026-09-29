<script lang="ts">
  import { api, ApiError } from "../api";
  import { describeVersion, explainVersionError, lineDiff, type DiffRow, type NoteVersion, type VersionList } from "../versionText";

  let { note, writable, dirty, onRestored }: {
    note: { id: string; vaultId: string }; writable: boolean; dirty: boolean; onRestored: () => void;
  } = $props();
  let list = $state<VersionList | null>(null);
  let error = $state("");
  let notice = $state("");
  let open = $state<number | null>(null);
  let rows = $state<DiffRow[] | null>(null);
  let loadingDiff = $state(false);
  let restoring = $state(false);
  let shown = $state(20);

  const message = (e: unknown) => e instanceof ApiError ? explainVersionError(e.status) : e instanceof Error ? e.message : String(e);

  async function load(limit = 100) {
    try { list = await api.noteVersions(note.vaultId, note.id, limit); }
    catch (e) { error = message(e); }
  }

  async function toggle(version: NoteVersion) {
    if (open === version.revision) { open = null; return; }
    open = version.revision; rows = null; error = ""; notice = "";
    loadingDiff = true;
    try {
      const shownVersion = await api.noteVersion(note.vaultId, note.id, version.revision);
      if (open === version.revision) rows = lineDiff(shownVersion.current, shownVersion.text);
    } catch (e) { error = message(e); }
    finally { loadingDiff = false; }
  }

  async function restore(version: NoteVersion) {
    const { who, when } = describeVersion(version);
    if (restoring || !window.confirm(`Die Notiz bekommt wieder den Text vom ${when} (${who}). Das ist eine neue Änderung: alle Versionen dazwischen bleiben erhalten.`)) return;
    restoring = true; error = "";
    try {
      const result = await api.restoreVersion(note.vaultId, note.id, version.revision);
      notice = result.changed ? "Version wiederhergestellt." : "Die Notiz hatte diesen Text schon.";
      open = null;
      onRestored();
      await load();
    } catch (e) { error = message(e); }
    finally { restoring = false; }
  }

  async function showMore() {
    if (list && shown >= list.versions.length && list.versions.length < list.total) await load(500);
    shown = list ? list.versions.length : shown;
  }

  $effect(() => { void load(); });
</script>

<section class="versions" aria-label="Versionen">
  <h4>Versionen</h4>
  {#if notice}<p class="feedback" role="status">{notice}</p>{/if}
  {#if error}<p class="feedback error" role="alert">{error}</p>{/if}
  {#if !list}
    {#if !error}<p role="status">Versionen werden geladen…</p>{/if}
  {:else if list.versions.length === 0}
    <p>Noch keine Versionen.</p>
  {:else}
    <ol>
      {#each list.versions.slice(0, shown) as version, index (version.revision)}
        {@const label = describeVersion(version)}
        <li>
          <button class="version" aria-expanded={open === version.revision} onclick={() => void toggle(version)}>
            <strong>{label.who}{index === 0 ? " · aktueller Stand" : ""}</strong><span>{label.when}</span>
          </button>
          {#if open === version.revision}
            <div class="version-detail">
              {#if loadingDiff}<p role="status">Wird geladen…</p>
              {:else if rows && (index === 0 || rows.length === 0)}<p>So sieht die Notiz gerade aus.</p>
              {:else if rows}
                <p>Was sich gegenüber jetzt ändern würde:</p>
                <div class="diff">{#each rows as row}<div class={row.kind}>{row.text || " "}</div>{/each}</div>
                {#if writable}
                  <button class="primary" disabled={dirty || restoring} onclick={() => void restore(version)}>Diese Version wiederherstellen</button>
                  {#if dirty}<p class="hint">Speichere oder verwirf zuerst deine Änderungen.</p>{/if}
                {/if}
              {/if}
            </div>
          {/if}
        </li>
      {/each}
    </ol>
    {#if list.total > shown && shown < 500}
      <button class="quiet" onclick={() => void showMore()}>{list.total - Math.min(shown, list.versions.length)} ältere Versionen zeigen</button>
    {/if}
  {/if}
</section>
