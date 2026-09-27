<script lang="ts">
  import { onMount } from "svelte";
  import { api, type Note, type Vault } from "../api";
  import { normalizeNotePath } from "../noteContent";
  import NoteEditor from "./NoteEditor.svelte";
  import FileViewer from "./FileViewer.svelte";
  import { fileKindLabel } from "../fileKinds";
  import SharePanel from "./SharePanel.svelte";
  import Icon from "./Icon.svelte";
  let { vault, onDirtyChange, onPermissions = () => {}, onNotesLoaded = () => {}, requestedNote = null }: { vault: Vault; onDirtyChange: (dirty: boolean) => void; onPermissions?: (permissions: string[]) => void; onNotesLoaded?: (notes: Note[]) => void; requestedNote?: Note | null } = $props();
  let closedFolders = $state<string[]>([]);
  let lastRequest: Note | null = null;
  $effect(() => { if (requestedNote && requestedNote !== lastRequest) { lastRequest = requestedNote; select(requestedNote); } });
  $effect(() => { onNotesLoaded(notes); });
  function toggleFolder(path: string) { closedFolders = closedFolders.includes(path) ? closedFolders.filter(p => p !== path) : [...closedFolders, path]; }
  function childFolders(prefix: string): string[] {
    return [...new Set(visible.filter(n => n.path.startsWith(prefix)).map(n => n.path.slice(prefix.length)).filter(p => p.includes("/")).map(p => prefix + p.split("/")[0]))].sort((a, b) => a.localeCompare(b, "de"));
  }
  function directNotes(prefix: string): Note[] { return visible.filter(n => n.path.startsWith(prefix) && !n.path.slice(prefix.length).includes("/")); }
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
  let similar = $state<import("../api").SimilarNote[] | null>(null);
  let similarLoading = $state(false);
  let sharing = $state<{ kind: "entry"; noteId: string; path: string } | { kind: "folder"; path: string } | null>(null);
  let alive = true;
  const folders = $derived([...new Set(notes.flatMap(n => { const parts = n.path.split("/"); return parts.slice(0, -1).map((_, i) => parts.slice(0, i + 1).join("/")); }))].sort());
  const visible = $derived(notes.filter(n => (!folder || n.path.startsWith(`${folder}/`)) && n.path.toLocaleLowerCase().includes(search.toLocaleLowerCase().trim()))
    .sort((a, b) => sort === "recent" ? b.createdAt.localeCompare(a.createdAt) : a.path.localeCompare(b.path, "de", { numeric: true })));

  function setDirty(value: boolean) { dirty = value; onDirtyChange(value || creating); }
  function mayLeave() { return !dirty || window.confirm("Ungespeicherte Änderungen verwerfen? Du kannst die Notiz vorher speichern oder als Markdown exportieren."); }
  function select(note: Note | null) { if (selected?.id === note?.id || !mayLeave()) return; selected = note; sharing = null; similar = null; setDirty(false); }
  async function showSimilar() {
    if (!selected || similarLoading) return;
    if (similar) { similar = null; return; }
    similarLoading = true;
    try { similar = await api.similarNotes(vault.id, selected.id); }
    catch (e) { error = e instanceof Error ? e.message : "Ähnliche Notizen konnten nicht geladen werden."; }
    finally { similarLoading = false; }
  }
  function openSimilar(path: string) {
    const found = notes.find(n => n.path === path);
    const related = similar?.find(n => n.path === path);
    if (found) select(found);
    else if (related) select({ id: related.noteId, vaultId: vault.id, path: related.path, noteLevel: 1, createdBy: "", createdAt: new Date().toISOString() });
  }

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


{#snippet tree(prefix: string, depth: number)}
  {#each childFolders(prefix) as path (path)}
    <div class="folder-branch">
      <button class="folder-toggle" style={`--depth:${depth}`} aria-label={`Ordner ${path}`} aria-expanded={!closedFolders.includes(path) || !!search} onclick={() => toggleFolder(path)}><span aria-hidden="true">{closedFolders.includes(path) && !search ? "›" : "⌄"}</span><Icon name="folder" size={17} /><span>{path.split("/").pop()}</span><small>{visible.filter(n => n.path.startsWith(path + "/")).length}</small></button>
      {#if !closedFolders.includes(path) || search}{@render tree(path + "/", depth + 1)}{/if}
    </div>
  {/each}
  {#each directNotes(prefix) as note (note.id)}
    <button class="note-row" style={`--depth:${depth}`} class:active={selected?.id === note.id} aria-current={selected?.id === note.id ? "true" : undefined} onclick={() => select(note)}><Icon name="notes" size={17} /><span class="note-row-text"><span class="note-name">{note.kind === "FILE" ? note.path.split("/").pop() : note.path.split("/").pop()?.replace(/\.md$/i, "")}</span>{#if note.kind === "FILE"}<span class="file-kind">{fileKindLabel(note.path)}</span>{/if}<span class="note-path">{note.path.includes("/") ? note.path.slice(0, note.path.lastIndexOf("/")) : "Ohne Ordner"}</span>{#if note.noteLevel === 101}<span class="locked">Verschlüsselt</span>{/if}</span></button>
  {/each}
{/snippet}

<div class="workspace-tools" class:note-open={selected !== null}>
  <div><h2 class="section-title">Notizen & Dateien</h2><p class="hint">{loading && !notes.length ? "Übersicht wird geladen…" : `${loadedSummary(notes)} geladen${complete ? "" : " · weitere verfügbar"}`}</p></div>
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
      {@render tree("", 0)}
      {#if !visible.length && !loading}<p class="list-empty">{search || folder ? "Keine passenden Notizen." : complete ? "Noch keine Notizen vorhanden." : "Auf dieser Seite sind keine sichtbaren Notizen."}</p>{/if}
      {#if loading}<p class="list-empty" role="status">Notizen werden geladen…</p>{/if}
    </div>
    {#if !complete}<button class="secondary load-more" disabled={loading} onclick={() => load()}>Weitere Notizen laden</button>{/if}
  </section>
  <section class="note-content" aria-label="Ausgewählte Notiz">
    {#if sharing}{#key JSON.stringify(sharing)}<SharePanel {vault} target={sharing} onClose={() => sharing = null} />{/key}{/if}
    {#if selected}<div class="note-actions"><button class="quiet back-to-list" onclick={() => select(null)}>Zur Notizliste</button><span class="note-actions-right">{#if selected.kind !== "FILE"}<button class="quiet" aria-expanded={similar !== null} disabled={similarLoading} onclick={showSimilar}>Ähnliche Notizen</button>{/if}<button class="quiet" onclick={() => sharing = selected ? { kind: "entry", noteId: selected.id, path: selected.path } : null}>Freigabe</button></span></div>
    {#if similar}<section class="similar" aria-label="Ähnliche Notizen">{#if similar.length}<ul>{#each similar as other (other.noteId)}<li><button class="link" onclick={() => openSimilar(other.path)}>{other.path.split("/").pop()?.replace(/\.md$/i, "")}</button><span class="hint">{Math.round(other.similarity * 100)} % ähnlich{other.heading ? ` · „${other.heading}“` : ""}</span></li>{/each}</ul>{:else}<p class="hint">Noch nichts gefunden. Ähnlichkeiten entstehen beim Verlinken (KI → Verlinkung).</p>{/if}</section>{/if}{#key selected.id}{#if selected.kind === "FILE"}<FileViewer note={selected} {permissions} onChanged={changed} onDeleted={deleted} />{:else}<NoteEditor note={selected} {permissions} onChanged={changed} onDeleted={deleted} onDirtyChange={setDirty} />{/if}{/key}
    {:else}<div class="welcome-document"><svg viewBox="0 0 64 72" width="64" height="72" fill="none" aria-hidden="true"><path d="M10 3h29l15 15v51H10z" stroke="currentColor" stroke-width="2"/><path d="M39 3v16h15M20 32h24M20 42h24M20 52h15" stroke="currentColor" stroke-width="2"/></svg><h3>Dein Wissen, direkt im Browser.</h3><p>Wähle eine Notiz aus der Liste, um sie zu lesen{permissions.includes("WRITE") ? " oder zu bearbeiten" : ""}.</p>{#if permissions.includes("CREATE")}<button class="primary" onclick={() => showCreate = true}>Erste Gedanken festhalten</button>{/if}<p class="hint">Mit Obsidian verbunden. Gespeicherte Änderungen stehen auch deinen anderen Geräten zur Verfügung.</p></div>{/if}
  </section>
</div>
