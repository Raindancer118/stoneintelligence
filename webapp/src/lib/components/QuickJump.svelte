<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { api, type Note } from '../api';
  import Icon from './Icon.svelte';
  let { vaultId, areas, onNote, onArea, onClose }: {
    vaultId: string | null; areas: { id: string; label: string }[];
    onNote: (note: Note) => void; onArea: (area: string) => void; onClose: () => void;
  } = $props();
  let query = $state('');
  let active = $state(0);
  let panel: HTMLDivElement;
  let input: HTMLInputElement;
  let found = $state<Note[]>([]);
  let searching = $state(false);
  let run = 0;
  // Notizen sucht der Server (Titel und Pfad), Bereiche werden lokal gefiltert; nur die letzte Anfrage zählt.
  $effect(() => {
    const q = query.trim();
    const current = ++run;
    if (!q || !vaultId) { found = []; searching = false; return; }
    searching = true;
    const timer = setTimeout(async () => {
      try { const result = await api.searchNotes(vaultId, q, 20); if (current === run) found = result.notes; }
      catch { if (current === run) found = []; }
      finally { if (current === run) searching = false; }
    }, 200);
    return () => clearTimeout(timer);
  });
  const results = $derived([
    ...areas.filter(area => area.label.toLocaleLowerCase().includes(query.toLocaleLowerCase().trim()))
      .map(area => ({ id: area.id, label: area.label, detail: 'Bereich', note: null as Note | null })),
    ...found.map(note => ({ id: note.id, label: note.path.split('/').pop()!.replace(/\.md$/i, ''), detail: note.path, note })),
  ]);
  function choose(index: number) {
    const item = results[index];
    if (!item) return;
    if (item.note) onNote(item.note); else onArea(item.id);
  }
  function key(event: KeyboardEvent) {
    if (event.key === 'Escape') { event.preventDefault(); onClose(); }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault(); active = (active + (event.key === 'ArrowDown' ? 1 : -1) + results.length) % Math.max(results.length, 1);
      panel.querySelectorAll('.jump-result')[active]?.scrollIntoView?.({ block: 'nearest' });
    }
    if (event.key === 'Enter' && event.target === input) { event.preventDefault(); choose(active); }
    if (event.key === 'Tab') {
      const controls = [...panel.querySelectorAll<HTMLElement>('input,button,a[href]')];
      const first = controls[0], last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  }
  onMount(() => {
    const previous = document.activeElement as HTMLElement | null;
    void tick().then(() => input.focus());
    return () => previous?.focus();
  });
</script>
<svelte:window onkeydown={key} />
<div class="jump-backdrop">
  <div class="jump-dialog" role="dialog" aria-modal="true" aria-labelledby="jump-title" tabindex="-1" bind:this={panel}>
    <header><div><p class="eyebrow">Direkt ans Ziel</p><h2 id="jump-title">Schnellsprung</h2></div><button class="quiet" onclick={onClose}>Schließen <kbd>Esc</kbd></button></header>
    <div class="jump-search"><Icon name="search" /><input bind:this={input} aria-label="Ziel suchen" type="search" placeholder="Notiz, Pfad oder Bereich …" bind:value={query} oninput={() => active = 0} aria-controls="jump-results" /></div>
    <p class="hint">Titel und Pfade im ganzen Vault · ↑ ↓ auswählen · Enter öffnen</p>
    <div id="jump-results" class="jump-results">
      {#each results as item, i (item.id)}<button class="jump-result" class:highlighted={active === i} onclick={() => choose(i)}><Icon name={item.note ? 'notes' : item.id} /><span><strong>{item.label}</strong><small>{item.detail}</small></span><span aria-hidden="true">↵</span></button>{/each}
      {#if searching}<p class="empty" role="status">Suche läuft…</p>{:else if !results.length}<p class="empty">Kein passendes Ziel.</p>{/if}
    </div>
    <footer><a href="/datenschutz">Datenschutz</a><a href="https://tstieh.de/impressum">Impressum</a></footer>
  </div>
</div>
