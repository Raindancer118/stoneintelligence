<script lang="ts">
  import { onMount } from "svelte";
  import { api, ApiError, type Note, type NoteContent, type AuditEvent } from "../api";
  import { decodeContent, prepareUpdate, renderMarkdown, normalizeNotePath, downloadNote } from "../noteContent";

  let { note, permissions, onChanged, onDeleted, onDirtyChange }: {
    note: Note; permissions: string[]; onChanged: (note: Note) => void;
    onDeleted: () => void; onDirtyChange: (dirty: boolean) => void;
  } = $props();
  let content = $state<NoteContent | null>(null);
  let baseline = $state("");
  let draft = $state("");
  let loading = $state(true);
  let saving = $state(false);
  let busy = $state(false);
  let mode = $state<"read" | "edit">("read");
  let error = $state("");
  let conflict = $state(false);
  let savedAt = $state("");
  let renaming = $state(false);
  let newPath = $state("");
  let history = $state<AuditEvent[] | null>(null);
  let showHistory = $state(false);
  let historyLoading = $state(false);
  let alive = true;
  const deleteOperationId = crypto.randomUUID();
  const writable = $derived(permissions.includes("WRITE") && note.noteLevel !== 101);
  const dirty = $derived(content !== null && draft !== baseline);
  const preview = $derived(mode === "read" ? renderMarkdown(draft) : "");
  const words = $derived(draft.trim() ? draft.trim().split(/\s+/u).length : 0);
  $effect(() => { onDirtyChange(dirty || saving || busy); });

  const date = (value: string) => new Intl.DateTimeFormat("de-DE", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
  const message = (e: unknown) => e instanceof Error ? e.message : "Die Aktion ist fehlgeschlagen. Bitte erneut versuchen.";

  async function load() {
    if (dirty && !window.confirm("Ungespeicherte Änderungen verwerfen und den Serverstand laden? Du kannst deinen Entwurf vorher als Markdown exportieren.")) return;
    loading = true; error = "";
    try {
      const loaded = await api.noteContent(note.vaultId, note.id);
      const doc = decodeContent(loaded.updates);
      try {
        if (!alive) return;
        baseline = doc.getText("content").toString(); draft = baseline; content = loaded;
        conflict = false; savedAt = "";
      } finally { doc.destroy(); }
    } catch (e) { if (alive) error = message(e); }
    finally { if (alive) loading = false; }
  }

  async function save() {
    if (!content || !dirty || saving || !writable || loading || conflict) return;
    saving = true; error = "";
    try {
      const update = prepareUpdate(content.updates, draft);
      const saved = await api.saveContent(note.vaultId, note.id, content.revision, update);
      if (!alive) return;
      content = { revision: saved.revision, updates: [...content.updates, update] };
      baseline = draft;
      savedAt = new Intl.DateTimeFormat("de-DE", { timeStyle: "short" }).format(new Date());
      history = null; showHistory = false;
    } catch (e) {
      if (alive) {
        conflict = e instanceof ApiError && e.status === 409;
        error = conflict ? "Zwischenzeitlich geändert. Dein Entwurf bleibt erhalten. Exportiere ihn bei Bedarf, bevor du den aktuellen Serverstand lädst." : message(e);
      }
    } finally { if (alive) saving = false; }
  }

  async function rename() {
    if (busy) return;
    busy = true; error = "";
    try {
      const renamed = await api.renameNote(note.vaultId, note.id, normalizeNotePath(newPath));
      if (alive) { onChanged(renamed); renaming = false; history = null; showHistory = false; }
    } catch (e) { if (alive) error = message(e); }
    finally { if (alive) busy = false; }
  }

  async function remove() {
    if (busy || saving || !window.confirm(`„${note.path}“ endgültig löschen? Die Notiz wird auch auf verbundenen Geräten entfernt.${dirty ? " Dein ungespeicherter Entwurf geht verloren." : ""}`)) return;
    busy = true; error = "";
    try { await api.deleteNote(note.vaultId, note.id, deleteOperationId); if (alive) { onDirtyChange(false); onDeleted(); } }
    catch (e) { if (alive) error = message(e); }
    finally { if (alive) busy = false; }
  }

  async function toggleHistory() {
    showHistory = !showHistory;
    if (!showHistory || history || historyLoading) return;
    historyLoading = true;
    try { const events = await api.noteAudit(note.vaultId, note.id); if (alive) history = events; }
    catch (e) { if (alive) { error = message(e); showHistory = false; } }
    finally { if (alive) historyLoading = false; }
  }
  const actionLabels: Record<string, string> = { "note.created": "Notiz angelegt", "note.renamed": "Pfad geändert", "note.deleted": "Notiz gelöscht", "note.content-updated": "Im Browser gespeichert" };
  function beforeUnload(event: BeforeUnloadEvent) { if (dirty || saving || busy) { event.preventDefault(); event.returnValue = ""; } }
  function shortcut(event: KeyboardEvent) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") { event.preventDefault(); void save(); }
  }
  onMount(() => { if (note.noteLevel === 101) loading = false; else void load(); return () => { alive = false; onDirtyChange(false); }; });
</script>

<svelte:window onbeforeunload={beforeUnload} onkeydown={shortcut} />
<article class="document" aria-label="Notiz">
  <header class="document-heading">
    <div class="title-block"><p class="path">{note.path}</p><h2>{note.path.split("/").pop()?.replace(/\.md$/i, "")}</h2></div>
    <span class="save-state" aria-live="polite">{saving ? "Wird gespeichert…" : dirty ? "Ungespeicherte Änderungen" : savedAt ? `Gespeichert um ${savedAt}` : ""}</span>
  </header>
  <div class="document-toolbar">
    <div class="modes" aria-label="Ansicht">
      <button class:chosen={mode === "read"} aria-pressed={mode === "read"} onclick={() => mode = "read"}>Lesen</button>
      {#if writable}<button class:chosen={mode === "edit"} aria-pressed={mode === "edit"} disabled={!content || loading || saving} onclick={() => mode = "edit"}>Bearbeiten</button>{/if}
    </div>
    <div class="actions">
      <button class="quiet" disabled={!content || loading} onclick={() => downloadNote(note.path, draft)}>Markdown exportieren</button>
      {#if writable}<button class="primary" disabled={!dirty || loading || saving || conflict || busy} onclick={save}>{saving ? "Speichert…" : "Speichern"}</button>{/if}
    </div>
  </div>
  {#if error}<div class="feedback error" role="alert"><p>{error}</p><button class="secondary" disabled={loading || saving} onclick={load}>{conflict ? "Serverstand laden" : "Erneut laden"}</button></div>{/if}
  {#if note.noteLevel === 101}
    <div class="empty"><h3>Verschlüsselte Notiz</h3><p>Öffne diese Notiz im Obsidian-Plugin. Der Browser hat keinen Zugriff auf deinen Schlüssel.</p></div>
  {:else if loading}
    <p class="loading" role="status">Notiz wird geladen…</p>
  {:else if content}
    {#if mode === "edit"}
      <div class="writing"><label class="sr-only" for="note-body">Markdown-Inhalt</label><textarea id="note-body" aria-label="Markdown-Inhalt" spellcheck="true" bind:value={draft} disabled={saving || busy} placeholder="Deine Notiz beginnt hier…"></textarea><p class="hint">Markdown wird unterstützt. Speichern mit Strg+S oder ⌘S.</p></div>
    {:else}
      <div class="reading">{#if draft}<div class="markdown">{@html preview}</div>{:else}<div class="empty"><h3>Hier ist Platz für deine Gedanken.</h3><p>{writable ? "Wähle Bearbeiten, um diese Notiz zu füllen." : "Diese Notiz hat noch keinen Inhalt."}</p></div>{/if}</div>
    {/if}
    <footer class="document-footer"><span>{words} {words === 1 ? "Wort" : "Wörter"}</span><span>{writable ? "Änderungen werden beim Speichern übertragen." : "Du kannst diese Notiz lesen."}</span></footer>
  {/if}
  <details class="details"><summary>Details und Aktionen</summary>
    <dl><div><dt>Erstellt von</dt><dd>{note.createdBy}</dd></div><div><dt>Erstellt am</dt><dd>{date(note.createdAt)}</dd></div></dl>
    <div class="detail-actions">
      <button class="secondary" disabled={loading || saving || busy} onclick={load}>Inhalt aktualisieren</button>
      <button class="secondary" aria-expanded={showHistory} onclick={toggleHistory}>Änderungsverlauf</button>
      {#if permissions.includes("WRITE")}<button class="secondary" disabled={saving || busy} onclick={() => { newPath = note.path; renaming = !renaming; }}>Umbenennen / verschieben</button>{/if}
      {#if permissions.includes("DELETE")}<button class="danger" disabled={saving || busy} onclick={remove}>Notiz löschen</button>{/if}
    </div>
    {#if renaming}<form class="rename-form" onsubmit={(e) => { e.preventDefault(); void rename(); }}><label for="new-path">Neuer Pfad</label><input id="new-path" bind:value={newPath} required disabled={busy} /><button class="primary" disabled={busy}>Pfad speichern</button><button type="button" class="quiet" onclick={() => renaming = false}>Abbrechen</button></form>{/if}
    {#if showHistory}<section class="history" aria-label="Änderungsverlauf"><p class="hint">Anlage, Pfadänderungen und Speichervorgänge im Browser. Einzelne Live-Sync-Schritte werden hier nicht protokolliert.</p>{#if historyLoading}<p role="status">Verlauf wird geladen…</p>{:else if history?.length}<ol>{#each [...history].reverse() as event}<li><strong>{actionLabels[event.action] ?? event.action}</strong><span>{event.actor} · {date(event.occurredAt)}</span></li>{/each}</ol>{:else}<p>Noch keine Einträge.</p>{/if}</section>{/if}
  </details>
</article>

<style>
  .document { min-width: 0; background: var(--surface-raised); border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
  .document-heading { padding: 1.5rem 1.75rem 1rem; display: flex; gap: 1rem; align-items: start; justify-content: space-between; }
  .title-block { min-width: 0; } h2 { font-size: clamp(1.35rem, 2.4vw, 2rem); overflow-wrap: anywhere; margin: .2rem 0 0; }
  .path { font-size: .8rem; color: var(--ink-dim); overflow-wrap: anywhere; margin: 0; }
  .save-state { font-size: .8rem; color: var(--ink-dim); text-align: right; padding-top: .2rem; }
  .document-toolbar { display: flex; justify-content: space-between; gap: .75rem; flex-wrap: wrap; padding: .25rem 1.75rem .75rem; border-bottom: 1px solid var(--line); }
  .modes, .actions, .detail-actions { display: flex; gap: .4rem; flex-wrap: wrap; }
  .modes button { background: transparent; border: 0; border-bottom: 2px solid transparent; padding: .5rem .8rem; color: var(--ink-dim); }
  .modes button.chosen { border-color: var(--forest); color: var(--forest); font-weight: 700; }
  .reading, .writing { padding: 2rem 1.75rem; min-height: 23rem; }
  .markdown { max-width: 75ch; overflow-wrap: anywhere; line-height: 1.8; }
  .markdown :global(h1) { font-size: 1.9rem; } .markdown :global(h2) { font-size: 1.5rem; } .markdown :global(h3) { font-size: 1.2rem; }
  .markdown :global(pre) { background: var(--bg); padding: 1rem; border-radius: 5px; overflow: auto; }
  .markdown :global(code) { font-family: ui-monospace, monospace; font-size: .88em; }
  .markdown :global(blockquote) { border-left: 3px solid var(--line); margin-left: 0; padding-left: 1.2rem; color: var(--ink-dim); }
  .markdown :global(table) { display: block; max-width: 100%; overflow: auto; border-collapse: collapse; }
  .markdown :global(th), .markdown :global(td) { padding: .5rem .75rem; border: 1px solid var(--line); text-align: left; }
  textarea { width: 100%; min-height: 25rem; resize: vertical; padding: 1rem; line-height: 1.7; font-family: ui-monospace, monospace; font-size: .92rem; background: var(--surface); border: 1px solid var(--line); border-radius: 4px; color: var(--ink); }
  .document-footer { display: flex; flex-wrap: wrap; justify-content: space-between; gap: .5rem; padding: .8rem 1.75rem; font-size: .78rem; color: var(--ink-dim); border-top: 1px solid var(--line); }
  .details { border-top: 1px solid var(--line); padding: .75rem 1.75rem 1.25rem; background: var(--surface); }
  summary { min-height: 48px; display: list-item; align-content: center; cursor: pointer; font-size: .85rem; font-weight: 500; }
  dl { display: flex; flex-wrap: wrap; gap: 1rem 3rem; margin: .5rem 0 1rem; font-size: .85rem; } dt { color: var(--ink-dim); } dd { margin: .2rem 0; }
  .rename-form { display: flex; flex-wrap: wrap; align-items: center; gap: .6rem; margin-top: 1rem; } .rename-form input { flex: 1; min-width: 10rem; }
  .history { margin-top: 1.25rem; } .history ol { list-style: none; padding: 0; font-size: .85rem; } .history li { display: flex; flex-direction: column; padding: .75rem 0; border-top: 1px solid var(--line); } .history li span { color: var(--ink-dim); }
  .loading, .empty { padding: 2rem; color: var(--ink-dim); } .empty h3 { color: var(--ink); } .feedback { margin: 1rem 1.75rem; }
  @media (max-width: 700px) { .document-heading, .document-toolbar, .reading, .writing, .details, .document-footer { padding-left: 1rem; padding-right: 1rem; } .document-heading { flex-direction: column; gap: .3rem; } .save-state { text-align: left; } }
</style>
