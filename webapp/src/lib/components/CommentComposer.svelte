<script lang="ts">
  import { untrack } from "svelte";
  import { activeMention, insertMention, mentionToken, suggest, type Member } from "../commentText";

  /** `onSubmit` meldet, ob gespeichert wurde - nur dann wird das Feld geleert. */
  let { label, initial = "", submitLabel = "Senden", members, busy = false, onSubmit, onCancel }: {
    label: string; initial?: string; submitLabel?: string; members: Member[]; busy?: boolean;
    onSubmit: (body: string) => Promise<boolean>; onCancel?: () => void;
  } = $props();
  let text = $state(untrack(() => initial));
  let caret = $state(0);
  let index = $state(0);
  let dismissedAt = $state(-1);
  let field = $state<HTMLTextAreaElement | null>(null);
  const id = `composer-${Math.random().toString(36).slice(2, 8)}`;
  const mention = $derived.by(() => { const found = activeMention(text, caret); return found && found.start !== dismissedAt ? found : null; });
  const options = $derived(mention ? suggest(members.filter(m => mentionToken(m.displayName?.trim() || m.subject, m.subject)), mention.query, null) : []);
  const name = (m: Member) => m.displayName?.trim() || m.subject;

  function sync() { caret = field?.selectionStart ?? text.length; index = 0; }
  function pick(member: Member) {
    if (!mention) return;
    const next = insertMention(text, mention.start, caret, name(member), member.subject);
    text = next.text;
    queueMicrotask(() => { field?.focus(); field?.setSelectionRange(next.caret, next.caret); caret = next.caret; });
  }
  async function submit() {
    const body = text.trim();
    if (!body || busy) return;
    if (await onSubmit(body)) { text = ""; caret = 0; }
  }
  function keydown(event: KeyboardEvent) {
    if (options.length) {
      if (event.key === "ArrowDown") { event.preventDefault(); index = (index + 1) % options.length; return; }
      if (event.key === "ArrowUp") { event.preventDefault(); index = (index - 1 + options.length) % options.length; return; }
      if (event.key === "Enter" || event.key === "Tab") { event.preventDefault(); pick(options[index]!); return; }
      if (event.key === "Escape") { event.preventDefault(); dismissedAt = mention!.start; return; }
    }
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) { event.preventDefault(); void submit(); }
    if (event.key === "Escape" && onCancel) { event.preventDefault(); onCancel(); }
  }
</script>
<form class="composer" onsubmit={(event) => { event.preventDefault(); void submit(); }}>
  <label class="sr-only" for={id}>{label}</label>
  <div class="composer-field">
    <textarea {id} bind:this={field} bind:value={text} rows="3" placeholder="Schreibe einen Kommentar. Mit @ erwähnst du jemanden." maxlength="4000" disabled={busy}
      role="combobox" aria-autocomplete="list" aria-expanded={options.length > 0} aria-controls={`${id}-list`}
      oninput={sync} onkeyup={sync} onclick={sync} onkeydown={keydown}></textarea>
    {#if options.length}
      <ul class="mention-list" id={`${id}-list`} role="listbox" aria-label="Mitglieder">
        {#each options as member, i (member.subject)}
          <li role="option" aria-selected={i === index}><button type="button" class="mention-option" class:chosen={i === index} onmousedown={(event) => { event.preventDefault(); pick(member); }}>{name(member)}</button></li>
        {/each}
      </ul>
    {/if}
  </div>
  <div class="composer-actions">
    <button class="primary" disabled={busy || !text.trim()}>{busy ? "Wird gesendet…" : submitLabel}</button>
    {#if onCancel}<button type="button" class="quiet" disabled={busy} onclick={onCancel}>Abbrechen</button>{/if}
  </div>
</form>
