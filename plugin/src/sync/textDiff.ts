import diff from "fast-diff";
import type * as Y from "yjs";

/** Eine Aenderung in Koordinaten des AUSGANGStextes - direkt als CodeMirror-`ChangeSpec` verwendbar. */
export interface TextChange {
  from: number;
  to: number;
  insert: string;
}

/**
 * Minimale Aenderungen von `current` nach `next`. Positionen beziehen sich auf `current` (so
 * erwartet CodeMirror ein Array von Changes in einer Transaktion).
 */
export function textChanges(current: string, next: string): TextChange[] {
  if (current === next) {
    return [];
  }
  const changes: TextChange[] = [];
  let pos = 0;
  for (const [op, chunk] of diff(current, next)) {
    if (op === diff.EQUAL) {
      pos += chunk.length;
    } else if (op === diff.DELETE) {
      const last = changes[changes.length - 1];
      if (last && last.to === pos && last.insert === "") {
        last.to += chunk.length;
      } else {
        changes.push({ from: pos, to: pos + chunk.length, insert: "" });
      }
      pos += chunk.length;
    } else {
      const last = changes[changes.length - 1];
      if (last && last.to === pos) {
        last.insert += chunk;
      } else {
        changes.push({ from: pos, to: pos, insert: chunk });
      }
    }
  }
  return changes;
}

/**
 * Bringt `text` per minimalem Diff auf den Stand `next`, statt alles zu loeschen und neu
 * einzufuegen. Nur so bleibt eine lokale Aenderung (anderes Plugin, externer Editor, Offline-Edit)
 * als echte Einfuegung/Loeschung an IHRER Stelle im CRDT stehen und merged sauber mit
 * gleichzeitigen Aenderungen anderer Geraete - ein Komplett-Ersatz ueberschrieb bisher jede
 * parallele fremde Aenderung bzw. verdoppelte Inhalte beim Zusammenfuehren.
 */
export function applyTextChange(text: Y.Text, next: string, origin?: unknown): void {
  const changes = textChanges(text.toString(), next);
  if (changes.length === 0) {
    return;
  }
  const apply = (): void => {
    // Rueckwaerts anwenden: Positionen beziehen sich auf den Ausgangstext, spaetere Stellen
    // zuerst zu aendern haelt die frueheren gueltig.
    for (let i = changes.length - 1; i >= 0; i--) {
      const change = changes[i];
      if (change.to > change.from) {
        text.delete(change.from, change.to - change.from);
      }
      if (change.insert.length > 0) {
        text.insert(change.from, change.insert);
      }
    }
  };
  if (text.doc) {
    text.doc.transact(apply, origin);
  } else {
    apply();
  }
}
