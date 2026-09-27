<script lang="ts">
  let { name, size = 20 }: { name: string; size?: number } = $props();
  const paths: Record<string, string> = {
    notes: 'M5 3h10l4 4v14H5z M14 3v5h5 M8 12h8 M8 16h6',
    obsidian: 'm12 3 8 8-8 10-8-10z M12 3l-3 9 3 9 3-9z',
    ai: 'm12 3 2.6 6.4L21 12l-6.4 2.6L12 21l-2.6-6.4L3 12l6.4-2.6z',
    manage: 'M16 21v-3a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v3 M14 4a4 4 0 0 1 0 8 M20 21v-3a4 4 0 0 0-3-3.8 M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
    search: 'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
    panel: 'M3 4h18v16H3z M9 4v16 M13 9l3 3-3 3',
    arrow: 'M4 12h16 M14 6l6 6-6 6',
    folder: 'M3 6h7l2 2h9v12H3z',
  };
</script>
<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d={paths[name] ?? paths.notes} /></svg>
