<script lang="ts">
  let { name, size = 20 }: { name: string; size?: number } = $props();
  const paths: Record<string, string> = {
    notes: 'M5 3h10l4 4v14H5z M14 3v5h5 M8 12h8 M8 16h6',
    obsidian: 'm12 3 8 8-8 10-8-10z M12 3l-3 9 3 9 3-9z',
    ai: 'M12 2 2 7l10 5 10-5z M2 17l10 5 10-5 M2 12l10 5 10-5',
    manage: 'M16 21v-3a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v3 M14 4a4 4 0 0 1 0 8 M20 21v-3a4 4 0 0 0-3-3.8 M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
    ask: 'M4 4h16v12H9l-5 4z M9.5 8.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .7-1 1.4',
    search: 'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
    panel: 'M3 4h18v16H3z M9 4v16 M13 9l3 3-3 3',
    arrow: 'M4 12h16 M14 6l6 6-6 6',
    folder: 'M3 6h7l2 2h9v12H3z',
    settings: 'M21 4h-7 M10 4H3 M21 12h-9 M8 12H3 M21 20h-5 M12 20H3 M14 2v4 M8 10v4 M16 18v4',
    sun: 'M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M12 2v2 M12 20v2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M2 12h2 M20 12h2 M6.3 17.7l-1.4 1.4 M19.1 4.9l-1.4 1.4',
    moon: 'M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z',
  };
</script>
<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="square" stroke-linejoin="round" aria-hidden="true"><path d={paths[name] ?? paths.notes} /></svg>
