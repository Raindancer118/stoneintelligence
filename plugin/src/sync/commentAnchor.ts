/**
 * Kommentare an Textstellen (ADR 0016): gespeichert wird das Zitat mit etwas Umgebung, nie eine Position.
 * Gesucht wird im "normalisierten" Text - Markdown-Zeichen weg, Leerraum zusammengefasst -, damit eine
 * Markierung im Web (gerenderter Text) und im Editor (Markdown-Quelltext) dieselbe Stelle meint.
 * Die Webapp nutzt eine exakte Kopie (webapp/src/lib/commentAnchor.ts, per Test abgesichert).
 */

export interface CommentAnchor { quote: string; prefix: string; suffix: string; }

const CONTEXT = 80;
const MAX_QUOTE = 1000;

export function normalize(text: string): string {
  return text
    .replace(/!?\[\[([^\]|]*\|)?([^\]]*)\]\]/g, "$2")
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/^[ \t]{0,3}#{1,6}[ \t]+/gm, "")
    .replace(/^[ \t]*>[ \t]?/gm, "")
    .replace(/^[ \t]*(?:[-*+]|\d+[.)])[ \t]+(?:\[[ xX]\][ \t]+)?/gm, "")
    .replace(/\*\*(.+?)\*\*/gs, "$1")
    .replace(/(?<![\w])__(.+?)__(?![\w])/gs, "$1")
    .replace(/~~(.+?)~~/gs, "$1")
    .replace(/==(.+?)==/gs, "$1")
    .replace(/\*(\S(?:.*?\S)?)\*/gs, "$1")
    .replace(/(?<![\w])_(\S(?:.*?\S)?)_(?![\w])/gs, "$1")
    .replace(/`([^`]*)`/g, "$1")
    .replace(/\s+/g, " ")
    .trim();
}

function occurrences(haystack: string, needle: string): number[] {
  const found: number[] = [];
  for (let at = haystack.indexOf(needle); at !== -1; at = haystack.indexOf(needle, at + 1)) found.push(at);
  return found;
}

/** `nearRaw`: Zeichenzahl im Quelltext vor der Auswahl - entscheidet, welcher von mehreren gleichen Treffern gemeint ist. */
export function makeAnchor(source: string, selected: string, nearRaw?: number): CommentAnchor | null {
  const quote = normalize(selected).slice(0, MAX_QUOTE).trim();
  if (!quote) return null;
  const text = normalize(source);
  const hits = occurrences(text, quote);
  if (hits.length === 0) return { quote, prefix: "", suffix: "" };
  const target = nearRaw === undefined ? 0 : normalize(source.slice(0, nearRaw)).length;
  const at = hits.reduce((best, hit) => (Math.abs(hit - target) < Math.abs(best - target) ? hit : best));
  return {
    quote,
    prefix: text.slice(Math.max(0, at - CONTEXT), at),
    suffix: text.slice(at + quote.length, at + quote.length + CONTEXT),
  };
}

function commonSuffix(a: string, b: string): number {
  let n = 0;
  while (n < a.length && n < b.length && a[a.length - 1 - n] === b[b.length - 1 - n]) n++;
  return n;
}

function commonPrefix(a: string, b: string): number {
  let n = 0;
  while (n < a.length && n < b.length && a[n] === b[n]) n++;
  return n;
}

/** Position im normalisierten Text, oder `null`, wenn das Zitat nicht mehr vorkommt ("Stelle geändert"). */
export function findAnchor(source: string, anchor: CommentAnchor): number | null {
  const text = normalize(source);
  const quote = normalize(anchor.quote);
  if (!quote) return null;
  const hits = occurrences(text, quote);
  if (hits.length === 0) return null;
  let best = hits[0];
  let bestScore = -1;
  for (const hit of hits) {
    const score = commonSuffix(text.slice(0, hit), anchor.prefix) + commonPrefix(text.slice(hit + quote.length), anchor.suffix);
    if (score > bestScore) { best = hit; bestScore = score; }
  }
  return best;
}

export type PlacedState = "note" | "found" | "changed";
export interface Placed<T> { item: T; state: PlacedState; index: number | null; }

/** Ganze-Notiz-Kommentare zuerst, dann nach Stelle im Text, zuletzt die, deren Stelle sich geändert hat. */
export function placeAnchors<T extends { anchor: CommentAnchor | null }>(source: string, items: T[]): Placed<T>[] {
  const placed = items.map((item): Placed<T> => {
    if (!item.anchor) return { item, state: "note", index: null };
    const index = findAnchor(source, item.anchor);
    return { item, state: index === null ? "changed" : "found", index };
  });
  const rank = (p: Placed<T>) => (p.state === "note" ? 0 : p.state === "found" ? 1 : 2);
  return placed
    .map((p, order) => ({ p, order }))
    .sort((a, b) => rank(a.p) - rank(b.p) || (a.p.index ?? 0) - (b.p.index ?? 0) || a.order - b.order)
    .map(({ p }) => p);
}
