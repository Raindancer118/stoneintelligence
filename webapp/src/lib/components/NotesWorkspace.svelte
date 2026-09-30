<script lang="ts">
  import { onMount } from "svelte";
  import { api, type Note, type Vault } from "../api";
  import { normalizeNotePath } from "../noteContent";
  import NoteEditor from "./NoteEditor.svelte";
  import FileViewer from "./FileViewer.svelte";
  import { fileKindLabel } from "../fileKinds";
  import SharePanel from "./SharePanel.svelte";
  import Icon from "./Icon.svelte";
  import { withTransition } from "../motion";
  let { vault, onDirtyChange, onPermissions = () => {}, requestedNote = null }: { vault: Vault; onDirtyChange: (dirty: boolean) => void; onPermissions?: (permissions: string[]) => void; requestedNote?: Note | null } = $props();
  let lastRequest: Note | null = null;
  $effect(() => { if (requestedNote && requestedNote !== lastRequest) { lastRequest = requestedNote; select(requestedNote); } });
  /** Eine geladene Ebene des Baums: Unterordner und die Einträge direkt darin (ADR 0013). */
  interface Level { folders: import("../api").FolderChild[]; entries: Note[]; cursor: string | null; loading: boolean }
  let levels = $state<Record<string, Level>>({});
  let open = $state<string[]>([]);
  /** Zuletzt geöffneter Ordner - Ziel von "Ordner freigeben". */
  let folder = $state("");
  async function loadLevel(path: string, more = false) {
    const current = levels[path];
    if (current?.loading || (current && !more)) return;
    levels[path] = { folders: current?.folders ?? [], entries: current?.entries ?? [], cursor: current?.cursor ?? null, loading: true };
    try {
      const [children, page] = await Promise.all([more ? Promise.resolve(current!.folders) : api.folderChildren(vault.id, path),
        api.listFolder(vault.id, path, more ? current!.cursor ?? undefined : undefined)]);
      if (!alive) return;
      levels[path] = { folders: children, entries: [...(more ? current!.entries : []), ...page.notes], cursor: page.complete ? null : page.nextCursor, loading: false };
    } catch (e) {
      if (!alive) return;
      error = e instanceof Error ? e.message : "Der Ordner konnte nicht geladen werden.";
      if (current) levels[path] = { ...current, loading: false }; else delete levels[path];
    }
  }
  function toggleFolder(path: string) {
    folder = path;
    if (open.includes(path)) { open = open.filter(p => p !== path); return; }
    open = [...open, path];
    void loadLevel(path);
  }
  /** Zeigt eine Notiz im Baum: lädt die Ebenen bis zu ihr neu und öffnet ihre Ordner (nach Anlegen oder Verschieben). */
  async function reveal(path: string) {
    const parts = path.split("/").slice(0, -1);
    const chain = ["", ...parts.map((_, i) => parts.slice(0, i + 1).join("/"))];
    open = [...new Set([...open, ...chain.slice(1)])];
    for (const level of chain) delete levels[level];
    await Promise.all(chain.map(level => loadLevel(level)));
  }
  function sorted(list: Note[]) { return [...list].sort((a, b) => sort === "recent" ? b.createdAt.localeCompare(a.createdAt) : a.path.localeCompare(b.path, "de", { numeric: true })); }
  const loaded = $derived(Object.values(levels).flatMap(level => level.entries));
  const notes = $derived(loaded);
  let permissions = $state<string[]>([]);
  let selected = $state<Note | null>(null);
  let search = $state("");
  let sort = $state("path");
  let loading = $state(true);
  let error = $state("");
  let complete = $state(false);
  /** Treffer der Serversuche; {@code null}, solange nicht gesucht wird. */
  let hits = $state<Note[] | null>(null);
  let truncated = $state(false);
  let searching = $state(false);
  let searchRun = 0;
  let creating = $state(false);
  let newPath = $state("");
  let showCreate = $state(false);
  let dirty = $state(false);
  let similar = $state<import("../api").SimilarNote[] | null>(null);
  let similarLoading = $state(false);
  let sharing = $state<{ kind: "entry"; noteId: string; path: string } | { kind: "folder"; path: string } | null>(null);
  let alive = true;
  const visible = $derived(sorted(search.trim() ? hits ?? [] : notes));

  function setDirty(value: boolean) { dirty = value; onDirtyChange(value || creating); }
  function mayLeave() { return !dirty || window.confirm("Ungespeicherte Änderungen verwerfen? Du kannst die Notiz vorher speichern oder als Markdown exportieren."); }
  function select(note: Note | null) { if (selected?.id === note?.id || !mayLeave()) return; void withTransition(() => { selected = note; sharing = null; similar = null; setDirty(false); }, "note"); }
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

  /** Lädt die oberste Ebene neu und die geöffneten Ordner mit - nie den ganzen Vault. */
  async function load() {
    loading = true; error = ""; complete = false;
    try {
      const rights = api.permissions(vault.id);
      levels = {};
      await Promise.all(["", ...open].map(path => loadLevel(path)));
      permissions = await rights;
      if (!alive) return;
      onPermissions(permissions);
      complete = true;
    } catch (e) { if (alive) error = e instanceof Error ? e.message : "Notizen konnten nicht geladen werden."; }
    finally { if (alive) loading = false; }
  }

  // Suche läuft auf dem Server (Titel und Pfad); nur die jeweils letzte Anfrage zählt.
  $effect(() => {
    const query = search.trim();
    const run = ++searchRun;
    if (!query) { hits = null; truncated = false; searching = false; return; }
    searching = true;
    const timer = setTimeout(async () => {
      try {
        const found = await api.searchNotes(vault.id, query);
        if (run === searchRun && alive) { hits = found.notes; truncated = found.truncated; }
      } catch (e) { if (run === searchRun && alive) error = e instanceof Error ? e.message : "Die Suche ist gerade nicht erreichbar."; }
      finally { if (run === searchRun && alive) searching = false; }
    }, 250);
    return () => clearTimeout(timer);
  });

  async function create() {
    if (creating || !mayLeave()) return;
    creating = true; onDirtyChange(true); error = "";
    try {
      const path = normalizeNotePath(newPath);
      if (notes.some(n => n.path === path)) throw new Error("Eine Notiz mit diesem Pfad ist bereits vorhanden.");
      const note = await api.createNote(vault.id, path);
      if (!alive) return;
      selected = note; showCreate = false; newPath = ""; search = ""; dirty = false;
      void reveal(note.path);
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
  function changed(note: Note) {
    for (const [path, level] of Object.entries(levels)) levels[path] = { ...level, entries: level.entries.filter(n => n.id !== note.id) };
    selected = note;
    void reveal(note.path);
  }
  function deleted() {
    for (const [path, level] of Object.entries(levels)) levels[path] = { ...level, entries: level.entries.filter(n => n.id !== selected?.id) };
    selected = null; setDirty(false);
  }
  onMount(() => { void load(); return () => { alive = false; }; });
</script>


{#snippet row(note: Note, depth: number)}
  <button class="note-row" style={`--depth:${depth}`} class:active={selected?.id === note.id} aria-current={selected?.id === note.id ? "true" : undefined} onclick={() => select(note)}><Icon name="notes" size={17} /><span class="note-row-text"><span class="note-name">{note.kind === "FILE" ? note.path.split("/").pop() : note.path.split("/").pop()?.replace(/\.md$/i, "")}</span>{#if note.kind === "FILE"}<span class="file-kind">{fileKindLabel(note.path)}</span>{/if}<span class="note-path">{note.path.includes("/") ? note.path.slice(0, note.path.lastIndexOf("/")) : "Ohne Ordner"}</span>{#if note.noteLevel === 101}<span class="locked">Verschlüsselt</span>{/if}</span></button>
{/snippet}

{#snippet tree(path: string, depth: number)}
  {@const level = levels[path]}
  {#if level}
    {#each level.folders as child (child.path)}
      <div class="folder-branch">
        <button class="folder-toggle" style={`--depth:${depth}`} aria-label={`Ordner ${child.path}`} aria-expanded={open.includes(child.path)} onclick={() => toggleFolder(child.path)}><span class="chevron" aria-hidden="true">›</span><Icon name="folder" size={17} /><span>{child.path.split("/").pop()}</span></button>
        {#if open.includes(child.path)}{@render tree(child.path, depth + 1)}{/if}
      </div>
    {/each}
    {#each sorted(level.entries) as note (note.id)}{@render row(note, depth)}{/each}
    {#if level.cursor}<button class="quiet load-more" style={`--depth:${depth}`} disabled={level.loading} onclick={() => loadLevel(path, true)}>Weitere laden</button>{/if}
    {#if level.loading}<p class="list-empty" style={`--depth:${depth}`} role="status">Wird geladen…</p>{/if}
  {/if}
{/snippet}

<div class="workspace-tools" class:note-open={selected !== null}>
  <div><h2 class="section-title">Notizen & Dateien</h2><p class="hint">{loading && !notes.length ? "Übersicht wird geladen…" : `${loadedSummary(notes)} in den geöffneten Ordnern`}</p></div>
  <div class="tool-actions"><button class="secondary" disabled={loading || creating} onclick={() => load()}>Liste aktualisieren</button>{#if permissions.includes("CREATE")}<button class="primary" aria-expanded={showCreate} onclick={() => showCreate = !showCreate}>Neue Notiz</button>{/if}</div>
</div>
{#if error}<div class="feedback error" role="alert"><p>{error}</p><button class="secondary" disabled={loading} onclick={() => load()}>Erneut versuchen</button></div>{/if}
{#if showCreate}<form class="create-form" onsubmit={e => { e.preventDefault(); void create(); }}><div><label for="create-path">Titel oder Pfad der neuen Notiz</label><input id="create-path" bind:value={newPath} placeholder="Projekte/Ideen.md" required disabled={creating} /><p class="hint">Mit / kannst du die Notiz in einem Ordner ablegen.</p></div><button class="primary" disabled={creating}>{creating ? "Wird angelegt…" : "Notiz anlegen"}</button><button type="button" class="quiet" disabled={creating} onclick={() => showCreate = false}>Abbrechen</button></form>{/if}
<div class="notes-layout" class:has-selection={selected !== null}>
  <section class="note-index" aria-label="Notizliste">
    <label for="note-search">Notizen finden</label><input id="note-search" type="search" bind:value={search} placeholder="Titel oder Pfad suchen" />
    <div class="filters"><label>Sortierung<select bind:value={sort}><option value="path">Name A–Z</option><option value="recent">Neu angelegt</option></select></label></div>
    <button class="quiet share-folder" onclick={() => sharing = { kind: "folder", path: folder }}>{folder ? `Ordner „${folder.split("/").pop()}“ freigeben` : "Freigaben für den ganzen Vault"}</button>
    <div class="note-rows" aria-busy={loading}>
      {#if search.trim()}{#each visible as note (note.id)}{@render row(note, 0)}{/each}{:else}{@render tree("", 0)}{/if}
      {#if search.trim() && !visible.length && !searching}<p class="list-empty">Keine passenden Notizen.</p>{/if}
      {#if !search.trim() && !loading && levels[""] && !levels[""].folders.length && !levels[""].entries.length}<p class="list-empty">Noch keine Notizen vorhanden.</p>{/if}
      {#if search.trim() && truncated && !searching}<p class="hint">Weitere Treffer vorhanden. Gib mehr vom Titel oder Pfad ein.</p>{/if}
      {#if searching}<p class="list-empty" role="status">Suche läuft…</p>{:else if loading}<p class="list-empty" role="status">Notizen werden geladen…</p>{/if}
    </div>
  </section>
  <section class="note-content" aria-label="Ausgewählte Notiz">
    {#if sharing}{#key JSON.stringify(sharing)}<SharePanel {vault} target={sharing} onClose={() => sharing = null} />{/key}{/if}
    {#if selected}<div class="note-actions"><button class="quiet back-to-list" onclick={() => select(null)}>Zur Notizliste</button><span class="note-actions-right">{#if selected.kind !== "FILE"}<button class="quiet" aria-expanded={similar !== null} disabled={similarLoading} onclick={showSimilar}>Ähnliche Notizen</button>{/if}<button class="quiet" onclick={() => sharing = selected ? { kind: "entry", noteId: selected.id, path: selected.path } : null}>Freigabe</button></span></div>
    {#if similar}<section class="similar" aria-label="Ähnliche Notizen">{#if similar.length}<ul>{#each similar as other (other.noteId)}<li><button class="link" onclick={() => openSimilar(other.path)}>{other.path.split("/").pop()?.replace(/\.md$/i, "")}</button><span class="hint">{Math.round(other.similarity * 100)} % ähnlich{other.heading ? ` · „${other.heading}“` : ""}</span></li>{/each}</ul>{:else}<p class="hint">Noch nichts gefunden. Ähnlichkeiten entstehen beim Verlinken (KI → Verlinkung).</p>{/if}</section>{/if}{#key selected.id}{#if selected.kind === "FILE"}<FileViewer note={selected} {permissions} onChanged={changed} onDeleted={deleted} />{:else}<NoteEditor note={selected} {permissions} onChanged={changed} onDeleted={deleted} onDirtyChange={setDirty} />{/if}{/key}
    {:else}<div class="welcome-document"><svg viewBox="0 0 64 72" width="64" height="72" fill="none" aria-hidden="true"><path d="M10 3h29l15 15v51H10z" stroke="currentColor" stroke-width="2"/><path d="M39 3v16h15M20 32h24M20 42h24M20 52h15" stroke="currentColor" stroke-width="2"/></svg><h3>Dein Wissen, direkt im Browser.</h3><p>Wähle eine Notiz aus der Liste, um sie zu lesen{permissions.includes("WRITE") ? " oder zu bearbeiten" : ""}.</p>{#if permissions.includes("CREATE")}<button class="primary" onclick={() => showCreate = true}>Erste Gedanken festhalten</button>{/if}<p class="hint">Mit Obsidian verbunden. Gespeicherte Änderungen stehen auch deinen anderen Geräten zur Verfügung.</p></div>{/if}
  </section>
</div>
