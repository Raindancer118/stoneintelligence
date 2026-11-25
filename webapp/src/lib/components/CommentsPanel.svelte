<script lang="ts">
  import { onMount } from "svelte";
  import Skeleton from "./Skeleton.svelte";
  import CommentComposer from "./CommentComposer.svelte";
  import { api, type CommentThreadView, type Note } from "../api";
  import { type CommentAnchor, placeAnchors } from "../commentAnchor";
  import { type Member, segments } from "../commentText";

  /** `threads === null`: wird geladen. `pending`: markierte Stelle, zu der gerade ein neuer Kommentar entsteht. */
  let { note, threads = $bindable(), source, pending, canManage, focusThread = null, error = "", onClose, onClearPending }: {
    note: Note; threads: CommentThreadView[] | null; source: string; pending: CommentAnchor | null; canManage: boolean;
    focusThread?: string | null; error?: string; onClose: () => void; onClearPending: () => void;
  } = $props();
  let members = $state<Member[]>([]);
  let showResolved = $state(false);
  let busy = $state("");
  let problem = $state("");
  let replyingTo = $state<string | null>(null);
  let editing = $state<string | null>(null);
  const message = (e: unknown) => e instanceof Error ? e.message : "Die Aktion ist fehlgeschlagen. Bitte erneut versuchen.";
  const time = (value: string) => new Intl.DateTimeFormat("de-DE", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
  const open = $derived((threads ?? []).filter(t => !t.resolved));
  const resolved = $derived((threads ?? []).filter(t => t.resolved));
  const placed = $derived(placeAnchors(source, showResolved ? [...open, ...resolved] : open));

  onMount(() => { api.listMembers(note.vaultId).then(list => members = list).catch(() => { /* ohne Vorschläge geht Tippen weiter */ }); });
  $effect(() => {
    const id = focusThread;
    if (id && threads?.some(t => t.id === id)) document.getElementById(`thread-${id}`)?.scrollIntoView?.({ block: "nearest" });
  });

  function replace(updated: CommentThreadView) {
    const list = threads ?? [];
    threads = list.some(t => t.id === updated.id) ? list.map(t => t.id === updated.id ? updated : t) : [...list, updated];
  }
  /** Führt eine Aktion aus; bei Fehler bleibt der Text im Feld und die Meldung des Servers steht darüber. */
  async function run(key: string, action: () => Promise<void>): Promise<boolean> {
    if (busy) return false;
    busy = key; problem = "";
    try { await action(); return true; }
    catch (e) { problem = message(e); return false; }
    finally { busy = ""; }
  }
  const startThread = (body: string) => run("new", async () => { replace(await api.startThread(note.vaultId, note.id, body, pending)); onClearPending(); });
  const reply = (threadId: string, body: string) => run(threadId, async () => { replace(await api.replyToThread(note.vaultId, threadId, body)); replyingTo = null; });
  const edit = (commentId: string, body: string) => run(commentId, async () => { replace(await api.editComment(note.vaultId, commentId, body)); editing = null; });
  const toggle = (thread: CommentThreadView) => run(thread.id, async () => {
    replace(await (thread.resolved ? api.reopenThread(note.vaultId, thread.id) : api.resolveThread(note.vaultId, thread.id)));
  });
  async function remove(thread: CommentThreadView, commentId: string) {
    if (!window.confirm("Diesen Kommentar löschen?")) return;
    await run(commentId, async () => {
      await api.deleteComment(note.vaultId, commentId);
      const left = thread.comments.filter(c => c.id !== commentId);
      threads = (threads ?? []).flatMap(t => t.id !== thread.id ? [t] : left.length ? [{ ...t, comments: left }] : []);
    });
  }
</script>
<aside class="comments" aria-label="Kommentare">
  <header class="comments-head">
    <h3>Kommentare</h3>
    <label class="comments-filter"><input type="checkbox" bind:checked={showResolved} />Gelöste zeigen{resolved.length ? ` (${resolved.length})` : ""}</label>
    <button class="quiet" onclick={onClose}>Schließen</button>
  </header>
  {#if error || problem}<p class="feedback error" role="alert">{problem || error}</p>{/if}
  <section class="comment-new" aria-label="Neuer Kommentar">
    {#if pending}
      <blockquote class="thread-quote"><span class="thread-quote-label">Zu dieser Stelle</span>„{pending.quote}“<button type="button" class="link" onclick={onClearPending}>Stelle entfernen</button></blockquote>
    {/if}
    <CommentComposer label={pending ? "Kommentar zur markierten Stelle" : "Kommentar zur Notiz"} {members} busy={busy === "new"} submitLabel="Kommentieren" onSubmit={startThread} />
    {#if !pending}<p class="hint">Um eine Stelle zu kommentieren, markiere sie im Lesemodus.</p>{/if}
  </section>
  {#if threads === null}
    <Skeleton kind="list" rows={3} label="Kommentare werden geladen…" />
  {:else if placed.length === 0}
    <p class="hint comments-empty">{resolved.length && !showResolved ? "Alle Diskussionen sind gelöst." : "Noch keine Kommentare."}</p>
  {:else}
    <ol class="threads">
      {#each placed as { item: thread, state } (thread.id)}
        <li>
          <article class="thread" class:resolved={thread.resolved} class:focused={focusThread === thread.id} id={`thread-${thread.id}`} aria-label={`Diskussion von ${thread.comments[0]?.authorName ?? ""}`}>
            {#if thread.anchor}
              <blockquote class="thread-quote">„{thread.anchor.quote}“{#if state === "changed"}<span class="badge">Stelle geändert</span>{/if}</blockquote>
            {/if}
            {#each thread.comments as comment (comment.id)}
              <div class="comment">
                <div class="comment-meta"><strong>{comment.authorName}</strong><time datetime={comment.createdAt}>{time(comment.createdAt)}</time>{#if comment.editedAt}<span>bearbeitet</span>{/if}</div>
                {#if editing === comment.id}
                  <CommentComposer label="Kommentar bearbeiten" initial={comment.body} {members} busy={busy === comment.id} submitLabel="Speichern" onSubmit={body => edit(comment.id, body)} onCancel={() => editing = null} />
                {:else}
                  <p class="comment-body">{#each segments(comment.body) as part}{#if part.kind === "mention"}<span class="mention">@{part.name}</span>{:else}{part.text}{/if}{/each}</p>
                  <div class="comment-actions">
                    {#if comment.mine}<button class="quiet" onclick={() => editing = comment.id}>Bearbeiten</button>{/if}
                    {#if comment.mine || canManage}<button class="quiet" disabled={busy === comment.id} onclick={() => remove(thread, comment.id)}>Löschen</button>{/if}
                  </div>
                {/if}
              </div>
            {/each}
            {#if thread.resolved}<p class="hint">Gelöst von {thread.resolvedByName ?? "Unbekannt"}{thread.resolvedAt ? `, ${time(thread.resolvedAt)}` : ""}</p>{/if}
            {#if replyingTo === thread.id}
              <CommentComposer label="Antwort" {members} busy={busy === thread.id} submitLabel="Antworten" onSubmit={body => reply(thread.id, body)} onCancel={() => replyingTo = null} />
            {:else}
              <div class="thread-actions">
                <button class="secondary" onclick={() => replyingTo = thread.id}>Antworten</button>
                <button class="quiet" disabled={busy === thread.id} onclick={() => toggle(thread)}>{thread.resolved ? "Wieder öffnen" : "Als gelöst markieren"}</button>
              </div>
            {/if}
          </article>
        </li>
      {/each}
    </ol>
  {/if}
</aside>
