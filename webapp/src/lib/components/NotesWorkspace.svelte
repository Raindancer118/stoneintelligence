<script lang="ts">
  import { onMount } from "svelte";
  import { api, type Note, type Vault } from "../api";
  import { normalizeNotePath } from "../noteContent";
  import NoteEditor from "./NoteEditor.svelte";
  import FileViewer from "./FileViewer.svelte";
  import { fileKindLabel } from "../fileKinds";
  import SharePanel from "./SharePanel.svelte";
  let { vault, onDirtyChange, onPermissions = () => {} }: { vault: Vault; onDirtyChange: (dirty: boolean) => void; onPermissions?: (permissions: string[]) => void } = $props();
  let notes = $state<Note[]>([]);
  let permissions = $state<string[]>([]);
  let selected = $state<Note | null>(null);
  let search = $state("");
  let folder = $state("");
  let sort = $state("path");
  let loading = $state(true);
  let error = $state("");
  let complete = $state(false);
  let cursor = $state<string | null>(null);
  let epoch = $state<string | null>(null);
  let creating = $state(false);
  let newPath = $state("");
  let showCreate = $state(false);
  let dirty = $state(false);
  let sharing = $state<{ kind: "entry"; noteId: string; path: string } | { kind: "folder"; path: string } | null>(null);
  let alive = true;
  const folders = $derived([...new Set(notes.map(n => n.path.includes("/") ? n.path.slice(0, n.path.lastIndexOf("/")) : ""))].filter(Boolean).sort());
  const visible = $derived(notes.filter(n => (!folder || n.path.startsWith(`${folder}/`)) && n.path.toLocaleLowerCase().includes(search.toLocaleLowerCase().trim()))
    .sort((a, b) => sort === "recent" ? b.createdAt.localeCompare(a.createdAt) : a.path.localeCompare(b.path, "de", { numeric: true })));

  function setDirty(value: boolean) { dirty = value; onDirtyChange(value || creating); }
  function mayLeave() { return !dirty || window.confirm("Ungespeicherte Änderungen verwerfen? Du kannst die Notiz vorher speichern oder als Markdown exportieren."); }
  function select(note: Note | null) { if (selected?.id === note?.id || !mayLeave()) return; selected = note; sharing = null; setDirty(false); }

  async function load(reset = false) {
    loading = true; error = "";
    try {
      const [page, rights] = await Promise.all([
        api.listNotes(vault.id, reset ? undefined : cursor ?? undefined),
        reset ? api.permissions(vault.id) : Promise.resolve(permissions),
      ]);
      if (!alive) return;
      if (!reset && epoch && page.epochId !== epoch) throw new Error("Die Liste hat sich geändert. Bitte aktualisiere sie.");
      if (!page.complete && (!page.nextCursor || page.nextCursor === cursor)) throw new Error("Die Liste konnte nicht vollständig geladen werden. Bitte aktualisiere sie.");
      permissions = rights; onPermissions(rights);
      notes = reset ? page.notes : [...new Map([...notes, ...page.notes].map(n => [n.id, n])).values()];
      epoch = page.epochId; cursor = page.nextCursor; complete = page.complete;
    } catch (e) { if (alive) error = e instanceof Error ? e.message : "Notizen konnten nicht geladen werden."; }
    finally { if (alive) loading = false; }
  }

  async function create() {
    if (creating || !mayLeave()) return;
    creating = true; onDirtyChange(true); error = "";
    try {
      const path = normalizeNotePath(newPath);
      if (notes.some(n => n.path === path)) throw new Error("Eine Notiz mit diesem Pfad ist bereits vorhanden.");
      const note = await api.createNote(vault.id, path);
      if (!alive) return;
      notes = [note, ...notes]; selected = note; showCreate = false; newPath = ""; search = ""; folder = ""; dirty = false;
    } catch (e) { if (alive) error = e instanceof Error ? e.message : "Die Notiz konnte nicht angelegt werden."; }
    finally { if (alive) { creating = false; onDirtyChange(dirty); } }
  }
  /** "3 Notizen" bzw. "1 Notiz, 2 Dateien" - Dateien nur erwähnen, wenn es welche gibt. */
  function loadedSummary(list: Note[]) {
    const files = list.filter(n => n.kind === "FILE").length;
    const count = list.length - files;
    const noteText = `${count} ${count === 1 ? "Notiz" : "Notizen"}`;
    return files ? `${noteText}, ${files} ${files === 1 ? "Datei" : "Dateien"}` : noteText;
  }
  function changed(note: Note) { notes = notes.map(n => n.id === note.id ? note : n); selected = note; }
  function deleted() { notes = notes.filter(n => n.id !== selected?.id); selected = null; setDirty(false); }
  onMount(() => { void load(true); return () => { alive = false; }; });
</script>

<div class="workspace-tools" class:note-open={selected !== null}>
  <div><h2 class="section-title">Deine Notizen</h2><p class="hint">{loading && !notes.length ? "Übersicht wird geladen…" : `${loadedSummary(notes)} geladen${complete ? "" : " · weitere verfügbar"}`}</p></div>
  <div class="tool-actions"><button class="secondary" disabled={loading || creating} onclick={() => load(true)}>Liste aktualisieren</button>{#if permissions.includes("CREATE")}<button class="primary" aria-expanded={showCreate} onclick={() => showCreate = !showCreate}>Neue Notiz</button>{/if}</div>
</div>
{#if error}<div class="feedback error" role="alert"><p>{error}</p><button class="secondary" disabled={loading} onclick={() => load(true)}>Erneut versuchen</button></div>{/if}
{#if showCreate}<form class="create-form" onsubmit={e => { e.preventDefault(); void create(); }}><div><label for="create-path">Titel oder Pfad der neuen Notiz</label><input id="create-path" bind:value={newPath} placeholder="Projekte/Ideen.md" required disabled={creating} /><p class="hint">Mit / kannst du die Notiz in einem Ordner ablegen.</p></div><button class="primary" disabled={creating}>{creating ? "Wird angelegt…" : "Notiz anlegen"}</button><button type="button" class="quiet" disabled={creating} onclick={() => showCreate = false}>Abbrechen</button></form>{/if}
<div class="notes-layout" class:has-selection={selected !== null}>
  <section class="note-index" aria-label="Notizliste">
    <label for="note-search">Notizen finden</label><input id="note-search" type="search" bind:value={search} placeholder="Titel oder Pfad suchen" />
    {#if !complete}<p class="hint search-scope">Die Suche durchsucht die geladenen Notizen.</p>{/if}
    <div class="filters"><label>Ordner<select bind:value={folder}><option value="">Alle Ordner</option>{#each folders as path}<option value={path}>{path}</option>{/each}</select></label><label>Sortierung<select bind:value={sort}><option value="path">Name A–Z</option><option value="recent">Neu angelegt</option></select></label></div>
    <button class="quiet share-folder" onclick={() => sharing = { kind: "folder", path: folder }}>{folder ? `Ordner „${folder.split("/").pop()}“ freigeben` : "Freigaben für den ganzen Vault"}</button>
    <div class="note-rows" aria-busy={loading}>
      {#each visible as note (note.id)}<button class="note-row" class:active={selected?.id === note.id} aria-current={selected?.id === note.id ? "true" : undefined} onclick={() => select(note)}><span class="note-name">{note.kind === "FILE" ? note.path.split("/").pop() : note.path.split("/").pop()?.replace(/\.md$/i, "")}</span>{#if note.kind === "FILE"}<span class="file-kind">{fileKindLabel(note.path)}</span>{/if}<span class="note-path">{note.path.includes("/") ? note.path.slice(0, note.path.lastIndexOf("/")) : "Ohne Ordner"}</span>{#if note.noteLevel === 101}<span class="locked">Verschlüsselt</span>{/if}</button>{/each}
      {#if !visible.length && !loading}<p class="list-empty">{search || folder ? "Keine passenden Notizen." : complete ? "Noch keine Notizen vorhanden." : "Auf dieser Seite sind keine sichtbaren Notizen."}</p>{/if}
      {#if loading}<p class="list-empty" role="status">Notizen werden geladen…</p>{/if}
    </div>
    {#if !complete}<button class="secondary load-more" disabled={loading} onclick={() => load()}>Weitere Notizen laden</button>{/if}
  </section>
  <section class="note-content" aria-label="Ausgewählte Notiz">
    {#if sharing}{#key JSON.stringify(sharing)}<SharePanel {vault} target={sharing} onClose={() => sharing = null} />{/key}{/if}
    {#if selected}<div class="note-actions"><button class="quiet back-to-list" onclick={() => select(null)}>Zur Notizliste</button><button class="quiet" onclick={() => sharing = selected ? { kind: "entry", noteId: selected.id, path: selected.path } : null}>Freigabe</button></div>{#key selected.id}{#if selected.kind === "FILE"}<FileViewer note={selected} {permissions} onChanged={changed} onDeleted={deleted} />{:else}<NoteEditor note={selected} {permissions} onChanged={changed} onDeleted={deleted} onDirtyChange={setDirty} />{/if}{/key}
    {:else}<div class="welcome-document"><svg viewBox="0 0 64 72" width="64" height="72" fill="none" aria-hidden="true"><path d="M10 3h29l15 15v51H10z" stroke="currentColor" stroke-width="2"/><path d="M39 3v16h15M20 32h24M20 42h24M20 52h15" stroke="currentColor" stroke-width="2"/></svg><h3>Dein Wissen, direkt im Browser.</h3><p>Wähle eine Notiz aus der Liste, um sie zu lesen{permissions.includes("WRITE") ? " oder zu bearbeiten" : ""}.</p>{#if permissions.includes("CREATE")}<button class="primary" onclick={() => showCreate = true}>Erste Gedanken festhalten</button>{/if}<p class="hint">Mit Obsidian verbunden. Gespeicherte Änderungen stehen auch deinen anderen Geräten zur Verfügung.</p></div>{/if}
  </section>
</div>
<style>
  .workspace-tools { display: flex; align-items: center; justify-content: space-between; gap: 1rem; margin-bottom: 1.2rem; flex-wrap: wrap; } .section-title { font-size: 1.15rem; margin: 0; } .workspace-tools p { margin: .25rem 0 0; } .tool-actions { display: flex; gap: .6rem; }
  .notes-layout { display: grid; grid-template-columns: minmax(15rem, 20rem) minmax(0, 1fr); gap: 1.5rem; align-items: start; }
  .note-index { background: var(--surface); border: 1px solid var(--line); border-radius: 6px; padding: 1rem; position: sticky; top: 1rem; }
  label { font-size: .82rem; font-weight: 500; display: block; margin-bottom: .3rem; } #note-search { width: 100%; }
  .filters { display: grid; grid-template-columns: 1fr 1fr; gap: .6rem; margin: 1rem 0; } .filters label { min-width: 0; color: var(--ink-dim); } select { width: 100%; margin-top: .3rem; }
  .note-rows { max-height: 60vh; overflow: auto; margin: 0 -.4rem; } .note-row { width: 100%; display: flex; flex-direction: column; align-items: start; border: 0; background: transparent; border-radius: 4px; padding: .85rem .65rem; text-align: left; color: var(--ink); gap: .12rem; }
  .note-row + .note-row { border-top: 1px solid var(--line); } .note-row:hover { background: var(--surface-raised); } .note-row.active { background: var(--forest-soft); color: var(--forest); }
  .note-name { font-weight: 500; overflow-wrap: anywhere; } .note-path, .locked, .file-kind { font-size: .75rem; color: var(--ink-dim); overflow-wrap: anywhere; } .locked { color: var(--rust); }
  .file-kind { text-transform: uppercase; letter-spacing: .05em; font-weight: 600; }
  .list-empty { font-size: .88rem; color: var(--ink-dim); padding: 1rem .5rem; } .load-more { width: 100%; margin-top: .75rem; }
  .note-content { min-width: 0; } .welcome-document { min-height: 32rem; display: flex; flex-direction: column; justify-content: center; align-items: start; padding: 3rem clamp(1.5rem, 5vw, 5rem); background: var(--surface); border-radius: 8px; }
  .welcome-document svg { color: var(--forest); margin-bottom: 2rem; } .welcome-document h3 { font-size: 1.7rem; max-width: 25ch; } .welcome-document p { max-width: 46ch; color: var(--ink-dim); } .welcome-document .hint { margin-top: 2.5rem; max-width: 50ch; }
  .create-form { display: flex; align-items: center; flex-wrap: wrap; gap: .8rem; background: var(--surface-raised); padding: 1.25rem; border: 1px solid var(--line); border-radius: 6px; margin-bottom: 1.5rem; } .create-form > div { flex: 1; min-width: min(20rem, 100%); } .create-form input { width: 100%; } .create-form .hint { margin: .4rem 0 0; }
  .search-scope { margin-bottom: 0; } .share-folder { width: 100%; margin-bottom: .75rem; } .note-actions { display: flex; justify-content: space-between; margin-bottom: .75rem; } .note-actions .back-to-list { margin-bottom: 0; } .back-to-list { display: none; margin-bottom: .75rem; }
  @media (max-width: 950px) { .notes-layout { grid-template-columns: minmax(13rem, 16rem) minmax(0, 1fr); gap: 1rem; } }
  @media (max-width: 700px) { .workspace-tools.note-open { display: none; } .notes-layout { display: block; } .has-selection .note-index { display: none; } .note-index { position: static; } .note-rows { max-height: none; } .back-to-list { display: inline-block; } .welcome-document { display: none; } .tool-actions { width: 100%; } .tool-actions button { flex: 1; } }
</style>
