<script lang="ts">
  /** Platzhalter in der Form des spaeteren Inhalts; `label` hoeren Screenreader. */
  /** `gated`: Die Verzoegerung uebernimmt schon ein LoadingGate - sonst wartet das CSS die 300 ms ab. */
  let { kind, label, rows = 5, gated = false }: { kind: "tree" | "document" | "list" | "panel" | "notes"; label: string; rows?: number; gated?: boolean } = $props();
  const widths = ["72%", "54%", "86%", "63%", "45%", "78%", "58%"];
  const lines = ["96%", "92%", "88%", "40%", "94%", "90%", "70%"];
</script>
<div class={`skeleton skeleton-${kind}`} class:skeleton-now={gated} role="status" aria-busy="true">
  <span class="sr-only">{label}</span>
  <div class="skeleton-shapes" aria-hidden="true">
    {#if kind === "tree"}
      {#each { length: rows } as _, i}<div class="skeleton-row"><i class="skeleton-icon"></i><i class="skeleton-bar" style={`width:${widths[i % widths.length]}`}></i></div>{/each}
    {:else if kind === "document"}
      <div class="skeleton-body">{#each lines as width}<i class="skeleton-bar skeleton-line" style={`width:${width}`}></i>{/each}</div>
    {:else if kind === "list"}
      {#each { length: rows } as _, i}<div class="skeleton-row skeleton-list-row"><i class="skeleton-bar skeleton-name" style={`width:${80 + (i * 37) % 70}px`}></i><i class="skeleton-bar skeleton-detail" style={`width:${widths[(i + 3) % widths.length]}`}></i><i class="skeleton-bar skeleton-meta"></i></div>{/each}
    {:else if kind === "notes"}
      <div class="skeleton-notes"><div class="skeleton-card">{#each { length: 5 } as _, i}<div class="skeleton-row"><i class="skeleton-icon"></i><i class="skeleton-bar" style={`width:${widths[i]}`}></i></div>{/each}</div><div class="skeleton-card skeleton-card-wide"><i class="skeleton-bar skeleton-path"></i><i class="skeleton-bar skeleton-title"></i>{#each { length: 4 } as _, i}<i class="skeleton-bar skeleton-line" style={`width:${widths[(i + 2) % widths.length]}`}></i>{/each}</div></div>
    {:else}
      <div class="skeleton-card"><i class="skeleton-bar skeleton-title"></i>{#each { length: rows } as _, i}<i class="skeleton-bar skeleton-line" style={`width:${widths[i % widths.length]}`}></i>{/each}</div>
    {/if}
  </div>
</div>
