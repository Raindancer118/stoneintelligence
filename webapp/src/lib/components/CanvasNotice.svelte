<script lang="ts">
  import type { Note } from "../api";

  let { note }: { note: Note } = $props();

  const name = $derived(note.path.split("/").pop() ?? note.path);
  const folder = $derived(note.path.includes("/") ? note.path.slice(0, note.path.lastIndexOf("/")) : "Ohne Ordner");
</script>

<article class="document" aria-label="Canvas">
  <header class="document-heading">
    <div class="title-block"><p class="path">{note.path}</p><h2>{name}</h2><p class="meta">Canvas · {folder}</p></div>
  </header>
  <div class="preview">
    <div class="empty"><h3>Dieses Canvas öffnest du in Obsidian</h3><p>Karten, Verbindungen und Texte werden dort gemeinsam und live bearbeitet. Im Browser gibt es dafür keine Ansicht.</p></div>
  </div>
</article>
