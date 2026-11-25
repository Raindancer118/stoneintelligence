<script lang="ts">
  import { onMount } from "svelte";
  import Icon from "./Icon.svelte";
  import { api, type CommentNotification } from "../api";

  /** Erwähnungen aus allen Vaults (ADR 0016); alle 60 s abgefragt, solange der Tab sichtbar ist. */
  let { onOpen }: { onOpen: (item: CommentNotification) => void } = $props();
  const POLL_MS = 60_000;
  let items = $state<CommentNotification[]>([]);
  let unread = $state(0);
  let menu = $state<HTMLDetailsElement | null>(null);
  let failed = $state(false);

  const time = (value: string) => new Intl.DateTimeFormat("de-DE", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
  const fileName = (path: string) => path.split("/").pop()?.replace(/\.md$/i, "") ?? path;

  async function refresh() {
    try { const loaded = await api.notifications(); items = loaded.items; unread = loaded.unread; failed = false; }
    catch { failed = true; }
  }
  async function open(item: CommentNotification) {
    if (menu) menu.open = false;
    if (!item.read) {
      items = items.map(i => i.id === item.id ? { ...i, read: true } : i);
      unread = Math.max(0, unread - 1);
      api.markNotificationsRead([item.id]).catch(() => { /* beim nächsten Abruf wieder ungelesen */ });
    }
    onOpen(item);
  }
  async function readAll() {
    try { await api.markNotificationsRead(); items = items.map(i => ({ ...i, read: true })); unread = 0; } catch { failed = true; }
  }
  onMount(() => {
    void refresh();
    const timer = window.setInterval(() => { if (document.visibilityState === "visible") void refresh(); }, POLL_MS);
    return () => window.clearInterval(timer);
  });
</script>
<details class="bell" bind:this={menu} ontoggle={() => { if (menu?.open) void refresh(); }}>
  <summary class="bell-button" aria-label={unread ? `Hinweise, ${unread} ungelesen` : "Hinweise"} title="Hinweise"><Icon name="bell" size={18} />{#if unread}<span class="bell-count" aria-hidden="true">{unread > 9 ? "9+" : unread}</span>{/if}</summary>
  <div class="bell-menu" role="group" aria-label="Hinweise">
    {#if items.length === 0}
      <p class="hint bell-empty">{failed ? "Hinweise sind gerade nicht erreichbar." : "Keine Hinweise."}</p>
    {:else}
      <ul>
        {#each items as item (item.id)}
          <li><button class="bell-item" class:unread={!item.read} onclick={() => open(item)}>
            <span class="bell-line"><strong>{item.actorName}</strong> hat dich erwähnt</span>
            <span class="path">{item.vaultName ? `${item.vaultName} · ` : ""}{fileName(item.notePath)}</span>
            <span class="bell-excerpt">{item.excerpt}</span>
            <time datetime={item.createdAt}>{time(item.createdAt)}</time>
          </button></li>
        {/each}
      </ul>
      {#if unread}<button class="quiet bell-all" onclick={readAll}>Alle als gelesen markieren</button>{/if}
    {/if}
  </div>
</details>
