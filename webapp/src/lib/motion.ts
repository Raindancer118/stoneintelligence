import { tick } from "svelte";

type Update = () => Promise<void>;
type ViewTransition = { finished: Promise<void> };
type TransitionDocument = Document & { startViewTransition?: (arg: Update | { update: Update; types: string[] }) => ViewTransition };

/** Art des Wechsels - `app.css` animiert Bereiche und Notizen unterschiedlich (`:active-view-transition-type`). */
export type TransitionKind = "area" | "note";

/**
 * Wendet eine Zustandsänderung als View Transition an (Bereichs-/Notizwechsel gleiten statt springen).
 * Ohne Browser-Unterstützung oder bei "Bewegung reduzieren" wird nur die Änderung ausgeführt.
 */
export async function withTransition(update: () => void, kind?: TransitionKind): Promise<void> {
  const doc = document as TransitionDocument;
  const reduced = typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (!doc.startViewTransition || reduced) { update(); return; }
  const apply: Update = async () => { update(); await tick(); };
  let transition: ViewTransition;
  try { transition = kind ? doc.startViewTransition({ update: apply, types: [kind] }) : doc.startViewTransition(apply); }
  catch { transition = doc.startViewTransition(apply); } // Browser ohne Transition-Typen
  // Eine abgebrochene Transition (z. B. schneller Doppelklick) ist kein Fehler - der Zustand ist trotzdem gesetzt.
  await transition.finished.catch(() => {});
}
