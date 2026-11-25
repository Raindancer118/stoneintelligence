<script lang="ts">
  import Skeleton from "./Skeleton.svelte";
  import { withTransition } from "../motion";
  import { onDestroy, onMount } from "svelte";
  import { LoadingGate } from "../loadingGate";
  import { api, ApiError, type CommentThreadView, type Note, type NoteContent, type NoteHistory } from "../api";
  import CommentsPanel from "./CommentsPanel.svelte";
  import { type CommentAnchor, makeAnchor } from "../commentAnchor";
  import { onCommentFocus, takeCommentFocus } from "../commentFocus";
  import { activityLines, describeEvent } from "../historyText";
  import VersionHistory from "./VersionHistory.svelte";
  import { decodeContent, prepareUpdate, renderMarkdown, normalizeNotePath, downloadNote } from "../noteContent";

  let { note, permissions, onChanged, onDeleted, onDirtyChange, onShare, onSimilar }: {
    note: Note; permissions: string[]; onChanged: (note: Note) => void;
    onDeleted: () => void; onDirtyChange: (dirty: boolean) => void;
    onShare?: () => void; onSimilar?: () => void;
  } = $props();
  let menu = $state<HTMLDetailsElement | null>(null);
  /** Menüeintrag ausführen und das Menü schließen. */
  function pick(action: () => void) { if (menu) menu.open = false; action(); }
  let skeleton = $state(false);
  const gate = new LoadingGate(visible => skeleton = visible);
  $effect(() => gate.set(loading));
  onDestroy(() => gate.dispose());
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
  let history = $state<NoteHistory | null>(null);
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
    try { const loaded = await api.noteHistory(note.vaultId, note.id); if (alive) history = loaded; }
    catch (e) { if (alive) { error = message(e); showHistory = false; } }
    finally { if (alive) historyLoading = false; }
  }
  async function afterRestore() {
    void load();
    try { const loaded = await api.noteHistory(note.vaultId, note.id); if (alive) history = loaded; } catch { /* Verlauf bleibt wie er war */ }
  }
  // Kommentare (ADR 0016): geladen beim Öffnen, danach alle 20 s abgefragt, solange der Tab sichtbar ist.
  const COMMENT_POLL_MS = 20_000;
  let threads = $state<CommentThreadView[] | null>(null);
  let commentsOpen = $state(false);
  let commentsError = $state("");
  let pendingAnchor = $state<CommentAnchor | null>(null);
  let focusThread = $state<string | null>(null);
  let selection = $state<{ text: string; x: number; y: number } | null>(null);
  let readingEl = $state<HTMLDivElement | null>(null);
  const commentable = $derived(note.noteLevel !== 101);
  const openThreads = $derived((threads ?? []).filter(t => !t.resolved).length);
  const canManage = $derived(permissions.includes("MANAGE"));

  async function loadComments() {
    try {
      const loaded = await api.listComments(note.vaultId, note.id);
      if (alive) { threads = loaded; commentsError = ""; }
    } catch (e) {
      // Die Notiz bleibt benutzbar; ohne Liste zeigt das Panel den Grund.
      if (alive) { commentsError = message(e); threads ??= []; }
    }
  }
  function openComments(thread: string | null = null) { commentsOpen = true; focusThread = thread; }
  function takeFocus() { const id = takeCommentFocus(note.id); if (id) openComments(id); }
  function captureSelection() {
    const picked = window.getSelection();
    const text = picked?.toString() ?? "";
    if (!picked || picked.isCollapsed || !text.trim() || !readingEl || !picked.anchorNode || !picked.focusNode
      || !readingEl.contains(picked.anchorNode) || !readingEl.contains(picked.focusNode)) { selection = null; return; }
    const rect = picked.getRangeAt(0).getBoundingClientRect?.();
    selection = { text, x: rect ? rect.left + rect.width / 2 : 0, y: rect ? rect.bottom : 0 };
  }
  function commentOnSelection() {
    if (!selection) return;
    const anchor = makeAnchor(draft, selection.text);
    if (!anchor) return;
    pendingAnchor = anchor; selection = null; window.getSelection()?.removeAllRanges();
    openComments();
  }
  function selectionChanged() { if (selection && window.getSelection()?.isCollapsed) selection = null; }
  function beforeUnload(event: BeforeUnloadEvent) { if (dirty || saving || busy) { event.preventDefault(); event.returnValue = ""; } }
  function shortcut(event: KeyboardEvent) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") { event.preventDefault(); void save(); }
  }
  onMount(() => {
    if (note.noteLevel === 101) loading = false; else { void load(); void loadComments(); }
    takeFocus();
    const off = onCommentFocus(takeFocus);
    const timer = window.setInterval(() => { if (document.visibilityState === "visible" && note.noteLevel !== 101) void loadComments(); }, COMMENT_POLL_MS);
    return () => { alive = false; off(); window.clearInterval(timer); onDirtyChange(false); };
  });
</script>

<svelte:window onbeforeunload={beforeUnload} onkeydown={shortcut} />
<svelte:document onselectionchange={selectionChanged} />
<article class="document" aria-label="Notiz">
  <header class="document-bar">
    <div class="title-block"><p class="path">{note.path}</p><h2>{note.path.split("/").pop()?.replace(/\.md$/i, "")}</h2></div>
    <div class="document-actions">
      <span class="save-state" aria-live="polite">{saving ? "Wird gespeichert…" : dirty ? "Nicht gespeichert" : savedAt ? `Gespeichert um ${savedAt}` : ""}</span>
      <div class="modes" role="group" aria-label="Ansicht">
        <button class:chosen={mode === "read"} aria-pressed={mode === "read"} onclick={() => void withTransition(() => mode = "read", "note")}>Lesen</button>
        {#if writable}<button class:chosen={mode === "edit"} aria-pressed={mode === "edit"} disabled={!content || loading || saving} onclick={() => void withTransition(() => mode = "edit", "note")}>Bearbeiten</button>{/if}
      </div>
      {#if writable && mode === "edit"}<button class="primary" disabled={!dirty || loading || saving || conflict || busy} onclick={save}>{saving ? "Speichert…" : "Speichern"}</button>{/if}
      {#if commentable}<button class="secondary" aria-expanded={commentsOpen} aria-label={openThreads ? `Kommentare, ${openThreads} offen` : "Kommentare"} onclick={() => commentsOpen ? commentsOpen = false : openComments()}>Kommentare{#if openThreads}<span class="count" aria-hidden="true">{openThreads}</span>{/if}</button>{/if}
      {#if onShare}<button class="secondary" onclick={onShare}>Freigeben</button>{/if}
      <details class="more-menu" bind:this={menu}><summary aria-label="Weitere Aktionen">Mehr</summary>
        <div class="menu" role="group" aria-label="Weitere Aktionen">
          {#if onSimilar}<button class="quiet" onclick={() => pick(onSimilar!)}>Ähnliche Notizen</button>{/if}
          <button class="quiet" aria-expanded={showHistory} onclick={() => pick(toggleHistory)}>Versionen und Verlauf</button>
          {#if permissions.includes("WRITE")}<button class="quiet" disabled={saving || busy} onclick={() => pick(() => { newPath = note.path; renaming = true; })}>Umbenennen / verschieben</button>{/if}
          <button class="quiet" disabled={!content || loading} onclick={() => pick(() => downloadNote(note.path, draft))}>Als Markdown herunterladen</button>
          <button class="quiet" disabled={loading || saving || busy} onclick={() => pick(load)}>Neu laden</button>
          {#if permissions.includes("DELETE")}<button class="quiet menu-danger" disabled={saving || busy} onclick={() => pick(remove)}>Notiz löschen</button>{/if}
        </div>
      </details>
    </div>
  </header>
  {#if renaming}<form class="rename-form" onsubmit={(e) => { e.preventDefault(); void rename(); }}><label for="new-path">Neuer Pfad</label><input id="new-path" bind:value={newPath} required disabled={busy} /><button class="primary" disabled={busy}>Pfad speichern</button><button type="button" class="quiet" onclick={() => renaming = false}>Abbrechen</button></form>{/if}
  {#if error}<div class="feedback error" role="alert"><p>{error}</p><button class="secondary" disabled={loading || saving} onclick={load}>{conflict ? "Serverstand laden" : "Erneut laden"}</button></div>{/if}
  <div class="document-split" class:with-comments={commentsOpen && commentable}>
  <div class="document-main">
  {#if note.noteLevel === 101}
    <div class="empty"><h3>Verschlüsselte Notiz</h3><p>Öffne diese Notiz im Obsidian-Plugin. Der Browser hat keinen Zugriff auf deinen Schlüssel.</p></div>
  {:else if loading || skeleton}
    {#if skeleton}<Skeleton kind="document" gated label="Notiz wird geladen…" />{:else}<p class="sr-only" role="status">Notiz wird geladen…</p>{/if}
  {:else if content}
    {#if mode === "edit"}
      <div class="writing"><label class="sr-only" for="note-body">Markdown-Inhalt</label><textarea id="note-body" aria-label="Markdown-Inhalt" spellcheck="true" bind:value={draft} disabled={saving || busy} placeholder="Deine Notiz beginnt hier…"></textarea><p class="hint">Markdown wird unterstützt. Speichern mit Strg+S oder ⌘S.</p></div>
    {:else}
      <div class="reading" bind:this={readingEl} onmouseup={captureSelection} onkeyup={captureSelection} role="presentation">{#if draft}<div class="markdown">{@html preview}</div>{:else}<div class="empty"><h3>Hier ist Platz für deine Gedanken.</h3><p>{writable ? "Wähle Bearbeiten, um diese Notiz zu füllen." : "Diese Notiz hat noch keinen Inhalt."}</p></div>{/if}</div>
    {/if}
    <footer class="document-footer"><span>{words} {words === 1 ? "Wort" : "Wörter"}</span>{#if !writable}<span>Nur lesen</span>{/if}</footer>
  {/if}
  </div>
  {#if commentsOpen && commentable}<CommentsPanel {note} bind:threads source={draft} pending={pendingAnchor} {canManage} {focusThread} error={commentsError} onClose={() => commentsOpen = false} onClearPending={() => pendingAnchor = null} />{/if}
  </div>
  {#if selection}<button class="selection-comment" style={`--x:${selection.x}px;--y:${selection.y}px`} onmousedown={(e) => e.preventDefault()} onclick={commentOnSelection}>Kommentieren</button>{/if}
  {#if showHistory}<div class="history-panel"><dl class="note-facts"><div><dt>Angelegt von</dt><dd>{note.createdBy}</dd></div><div><dt>Angelegt am</dt><dd>{date(note.createdAt)}</dd></div></dl>
  <section class="history" aria-label="Änderungsverlauf">{#if historyLoading}<Skeleton kind="list" rows={3} label="Verlauf wird geladen…" />{:else if history}<ul class="activity">{#each history.activity ? activityLines(history.activity) : [] as line}<li>{line}</li>{/each}</ul>{#if note.noteLevel !== 101}<VersionHistory {note} {writable} {dirty} onRestored={afterRestore} />{/if}<h4>Protokoll</h4>{#if history.events.length}<ol>{#each [...history.events].reverse() as event}<li><strong>{describeEvent(event)}</strong><span>{date(event.occurredAt)}</span></li>{/each}</ol>{:else}<p>Noch keine Einträge.</p>{/if}{/if}</section></div>{/if}
</article>
