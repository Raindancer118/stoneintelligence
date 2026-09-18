/**
 * Entscheidet rein funktional, welche Editor-Panes neu ans CRDT gebunden und welche geloest
 * werden muessen - ohne Obsidian-/CodeMirror-Abhaengigkeit, damit die Regeln testbar sind.
 *
 * <p>Hintergrund: die vorherige Fassung band ausschliesslich die EINE "aktive" Notiz und holte
 * die EditorView ueber `workspace.activeEditor?.editor.cm`. Beim `file-open`-Ereignis zeigt
 * `activeEditor` aber haeufig noch gar nicht auf die gerade geoeffnete Datei - in dem Fall wurde
 * schlicht NICHTS gebunden (kein yCollab, also auch keine fremden Cursor), ohne jede Fehlermeldung.
 * Das Vorgaengerprojekt `stonesync` hat das anders geloest: es zaehlt die tatsaechlich offenen
 * Markdown-Panes auf (`workspace.getLeavesOfType("markdown")` → `view.editor.cm`) und bindet jedes
 * davon - siehe codex-research-archive/sources/old-stonesync/plugin/src/sync/SyncManager.ts
 * (`openMarkdownEditors`/`bindOpenEditors`). Dieses Modul bildet genau diese Entscheidungslogik ab.
 */

/** Ein aktuell offenes Markdown-Pane: welcher Pfad in welcher konkreten EditorView-Instanz. */
export interface OpenEditor<V> {
  path: string;
  view: V;
}

export interface BindingPlan<V> {
  /** Panes, deren bisherige Bindung geloest werden muss (Notiz geschlossen oder View gewechselt). */
  unbind: Array<OpenEditor<V>>;
  /** Panes, die (neu) gebunden werden muessen. */
  bind: Array<OpenEditor<V>>;
}

/**
 * @param open   alle aktuell offenen Markdown-Panes, in Anzeige-Reihenfolge
 * @param bound  Pfad → EditorView, an die dieser Pfad gerade tatsaechlich gebunden ist
 */
export function planEditorBindings<V>(open: Array<OpenEditor<V>>, bound: Map<string, V>): BindingPlan<V> {
  // Dieselbe Datei kann in mehreren Panes offen sein. Nur das erste binden: zwei Views an EINER
  // Awareness-Instanz wuerden sich um die eine lokale Cursor-Position streiten (Obsidian haelt
  // mehrere Views derselben Datei ohnehin untereinander synchron).
  const seen = new Set<string>();
  const distinct: Array<OpenEditor<V>> = [];
  for (const entry of open) {
    if (seen.has(entry.path)) {
      continue;
    }
    seen.add(entry.path);
    distinct.push(entry);
  }

  const openByPath = new Map(distinct.map((entry) => [entry.path, entry.view]));
  const unbind: Array<OpenEditor<V>> = [];
  for (const [path, view] of bound) {
    if (openByPath.get(path) !== view) {
      // Notiz geschlossen, ODER sie liegt jetzt in einer anderen View (typisch: in EINEM Pane
      // wurde eine andere Datei geoeffnet - Obsidian verwendet dieselbe CM6-View weiter).
      unbind.push({ path, view });
    }
  }

  const bind = distinct.filter((entry) => bound.get(entry.path) !== entry.view);
  return { unbind, bind };
}
