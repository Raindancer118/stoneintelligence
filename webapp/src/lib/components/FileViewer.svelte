<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { api, type Note } from "../api";
  import { formatSize, previewKind } from "../fileKinds";

  let { note, permissions, onChanged, onDeleted }: {
    note: Note; permissions: string[]; onChanged: (note: Note) => void; onDeleted: () => void;
  } = $props();

  /** Bis zu dieser Größe wird die Datei gleich geladen; darüber erst auf Wunsch. */
  const AUTO_LOAD_BYTES = 25 * 1024 * 1024;

  const name = $derived(note.path.split("/").pop() ?? note.path);
  const folder = $derived(note.path.includes("/") ? note.path.slice(0, note.path.lastIndexOf("/")) : "Ohne Ordner");
  const kind = $derived(previewKind(note.path));
  const size = $derived(note.size ?? 0);

  let url = $state<string | null>(null);
  let loading = $state(false);
  let error = $state("");
  let busy = $state(false);
  let renaming = $state(false);
  let newPath = $state("");
  const deleteOperationId = crypto.randomUUID();
  let alive = true;

  async function load() {
    if (loading) return;
    loading = true; error = "";
    try {
      const blob = await api.fileBlob(note.vaultId, note.id);
      if (!alive) return;
      if (url) URL.revokeObjectURL(url);
      url = URL.createObjectURL(blob);
    } catch (e) {
      if (alive) error = e instanceof Error ? e.message : "Die Datei konnte nicht geladen werden.";
    } finally {
      if (alive) loading = false;
    }
  }

  onMount(() => { if (note.revision !== 0 && size <= AUTO_LOAD_BYTES) void load(); });
  onDestroy(() => { alive = false; if (url) URL.revokeObjectURL(url); });

  async function rename() {
    if (busy || !newPath.trim()) return;
    busy = true; error = "";
    try {
      const renamed = await api.renameNote(note.vaultId, note.id, newPath.trim());
      if (alive) { onChanged({ ...note, ...renamed }); renaming = false; }
    } catch (e) {
      if (alive) error = e instanceof Error ? e.message : "Die Datei konnte nicht umbenannt werden.";
    } finally {
      if (alive) busy = false;
    }
  }

  async function remove() {
    if (busy || !window.confirm(`„${note.path}“ endgültig löschen? Die Datei wird auch auf verbundenen Geräten entfernt.`)) return;
    busy = true; error = "";
    try {
      await api.deleteNote(note.vaultId, note.id, deleteOperationId);
      if (alive) onDeleted();
    } catch (e) {
      if (alive) error = e instanceof Error ? e.message : "Die Datei konnte nicht gelöscht werden.";
    } finally {
      if (alive) busy = false;
    }
  }
</script>

<article class="document" aria-label="Datei">
  <header class="document-heading">
    <div class="title-block"><p class="path">{note.path}</p><h2>{name}</h2><p class="meta">{formatSize(size)} · {folder}</p></div>
    {#if url}<a class="action secondary" href={url} download={name}>Herunterladen</a>{/if}
  </header>

  {#if error}<div class="feedback error" role="alert"><p>{error}</p><button class="secondary" disabled={loading} onclick={load}>Erneut laden</button></div>{/if}

  <div class="preview">
    {#if note.revision === 0}
      <div class="empty"><h3>Noch kein Inhalt</h3><p>Das Gerät, das die Datei angelegt hat, lädt sie gerade hoch.</p></div>
    {:else if !url && !loading && size > AUTO_LOAD_BYTES}
      <div class="empty"><h3>Große Datei</h3><p>Sie wird erst geladen, wenn du sie ansehen oder herunterladen möchtest.</p>
        <button class="primary" onclick={load}>Datei laden ({formatSize(size)})</button></div>
    {:else if loading}
      <p class="loading" role="status">Datei wird geladen…</p>
    {:else if url}
      {#if kind === "image"}
        <img src={url} alt={name} />
      {:else if kind === "pdf"}
        <iframe src={url} title={name}></iframe>
      {:else if kind === "video"}
        <!-- svelte-ignore a11y_media_has_caption -->
        <video src={url} controls aria-label={name}></video>
      {:else if kind === "audio"}
        <audio src={url} controls aria-label={name}></audio>
      {:else}
        <div class="empty"><h3>Keine Vorschau</h3><p>Für diese Datei gibt es keine Vorschau im Browser. Lade sie herunter, um sie zu öffnen.</p></div>
      {/if}
    {/if}
  </div>

  {#if permissions.includes("WRITE") || permissions.includes("DELETE")}
    <details class="details"><summary>Aktionen</summary>
      <div class="detail-actions">
        {#if permissions.includes("WRITE")}<button class="secondary" disabled={busy} onclick={() => { newPath = note.path; renaming = !renaming; }}>Umbenennen / verschieben</button>{/if}
        {#if permissions.includes("DELETE")}<button class="danger" disabled={busy} onclick={remove}>Datei löschen</button>{/if}
      </div>
      {#if renaming}<form class="rename-form" onsubmit={(e) => { e.preventDefault(); void rename(); }}><label for="new-file-path">Neuer Pfad</label><input id="new-file-path" bind:value={newPath} required disabled={busy} /><button class="primary" disabled={busy}>Pfad speichern</button><button type="button" class="quiet" onclick={() => renaming = false}>Abbrechen</button></form>{/if}
    </details>
  {/if}
</article>

<style>
  .document { min-width: 0; background: var(--surface-raised); border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
  .document-heading { padding: 1.5rem 1.75rem 1rem; display: flex; gap: 1rem; align-items: start; justify-content: space-between; flex-wrap: wrap; border-bottom: 1px solid var(--line); }
  .title-block { min-width: 0; } h2 { font-size: clamp(1.35rem, 2.4vw, 2rem); overflow-wrap: anywhere; margin: .2rem 0 0; }
  .path, .meta { font-size: .8rem; color: var(--ink-dim); overflow-wrap: anywhere; margin: 0; } .meta { margin-top: .35rem; }
  .action { display: inline-flex; align-items: center; min-height: 44px; text-decoration: none; }
  .preview { padding: 1.5rem 1.75rem; display: grid; place-items: center; min-height: 12rem; }
  .preview img { max-width: 100%; max-height: 70vh; object-fit: contain; border-radius: 4px; }
  .preview iframe { width: 100%; height: 75vh; border: 1px solid var(--line); border-radius: 4px; background: var(--surface); }
  .preview video { max-width: 100%; max-height: 70vh; } .preview audio { width: 100%; max-width: 32rem; }
  .empty { text-align: center; max-width: 36ch; color: var(--ink-dim); } .empty h3 { color: var(--ink); margin: 0 0 .4rem; } .empty .primary { margin-top: .75rem; min-height: 44px; }
  .loading { color: var(--ink-dim); }
  .feedback { margin: 1rem 1.75rem 0; }
  .details { border-top: 1px solid var(--line); padding: 1rem 1.75rem; } .details summary { cursor: pointer; font-weight: 500; }
  .detail-actions { display: flex; gap: .4rem; flex-wrap: wrap; margin-top: .75rem; }
  .rename-form { display: flex; flex-wrap: wrap; align-items: center; gap: .6rem; margin-top: 1rem; } .rename-form input { flex: 1; min-width: 10rem; }
</style>
