import * as Y from "yjs";
import { applyTextChange } from "./textDiff";

/**
 * Ein Canvas (JSON Canvas) als Yjs-Dokument (ADR 0018): `nodes` und `edges` sind Maps nach Id,
 * jeder Eintrag eine Map seiner Felder, der Text einer Textkarte ein Y.Text. `meta` haelt unbekannte
 * Schluessel der obersten Ebene. Die Reihenfolge der Karten (= Ebenen im Canvas) steckt je Eintrag
 * im reservierten Feld `_o`.
 */
export interface CanvasData {
  nodes: CanvasEntry[];
  edges: CanvasEntry[];
  [extra: string]: unknown;
}
export type CanvasEntry = { id: string; [field: string]: unknown };

const ORDER = "_o";
const FIELD_ORDER = ["id", "type", "x", "y", "width", "height"];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** `null` bei kaputtem JSON oder Eintraegen ohne Id - dann darf nichts angefasst werden. */
export function parseCanvas(source: string): CanvasData | null {
  if (source.trim() === "") {
    return { nodes: [], edges: [] };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(source);
  } catch {
    return null;
  }
  if (!isRecord(parsed)) {
    return null;
  }
  const { nodes = [], edges = [], ...rest } = parsed;
  if (!Array.isArray(nodes) || !Array.isArray(edges)) {
    return null;
  }
  const valid = (entry: unknown): entry is CanvasEntry => isRecord(entry) && typeof entry.id === "string" && entry.id !== "";
  if (!nodes.every(valid) || !edges.every(valid)) {
    return null;
  }
  return { nodes, edges, ...rest };
}

function entryJson(entry: CanvasEntry): string {
  const keys = Object.keys(entry);
  const first = FIELD_ORDER.filter((key) => key in entry);
  const rest = keys.filter((key) => !FIELD_ORDER.includes(key)).sort();
  const ordered: Record<string, unknown> = {};
  for (const key of [...first, ...rest]) {
    ordered[key] = entry[key];
  }
  return JSON.stringify(ordered);
}

/** Wie Obsidian es schreibt (Tabs, eine Karte je Zeile) - ein unveraenderter Canvas bleibt Byte fuer Byte gleich. */
export function serializeCanvas(data: CanvasData): string {
  const list = (key: string, entries: CanvasEntry[]) =>
    entries.length === 0 ? `\t"${key}":[]` : `\t"${key}":[\n${entries.map((entry) => `\t\t${entryJson(entry)}`).join(",\n")}\n\t]`;
  const { nodes, edges, ...rest } = data;
  const parts = [list("nodes", nodes), list("edges", edges), ...Object.entries(rest).map(([key, value]) => `\t${JSON.stringify(key)}:${JSON.stringify(value)}`)];
  return `{\n${parts.join(",\n")}\n}`;
}

function stable(value: unknown): string {
  if (Array.isArray(value)) {
    return `[${value.map(stable).join(",")}]`;
  }
  if (isRecord(value)) {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stable(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value) ?? "null";
}

/** Inhaltlich gleich, unabhaengig von der Reihenfolge der Schluessel. */
export function canvasEquals(a: CanvasData, b: CanvasData): boolean {
  return stable(a) === stable(b);
}

type EntryMap = Y.Map<Y.Map<unknown>>;

function entryMap(doc: Y.Doc, section: "nodes" | "edges"): EntryMap {
  return doc.getMap(section) as EntryMap;
}

function orderedIds(entries: EntryMap): string[] {
  const order = (id: string) => {
    const value = entries.get(id)?.get(ORDER);
    return typeof value === "number" ? value : Number.POSITIVE_INFINITY;
  };
  return [...entries.keys()].sort((a, b) => order(a) - order(b) || (a < b ? -1 : a > b ? 1 : 0));
}

function readEntry(map: Y.Map<unknown>): CanvasEntry {
  const entry: Record<string, unknown> = {};
  for (const [key, value] of map.entries()) {
    if (key !== ORDER) {
      entry[key] = value instanceof Y.Text ? value.toString() : value;
    }
  }
  return entry as CanvasEntry;
}

/** Der Canvas, den das Dokument gerade ergibt. Kanten zu geloeschten Karten bleiben im Dokument, erscheinen aber nicht. */
export function canvasOfDoc(doc: Y.Doc): CanvasData {
  const nodeMap = entryMap(doc, "nodes");
  const edgeMap = entryMap(doc, "edges");
  const nodes = orderedIds(nodeMap).map((id) => readEntry(nodeMap.get(id)!));
  const known = new Set(nodes.map((node) => node.id));
  const edges = orderedIds(edgeMap).map((id) => readEntry(edgeMap.get(id)!))
    .filter((edge) => known.has(String(edge.fromNode)) && known.has(String(edge.toNode)));
  const meta: Record<string, unknown> = {};
  for (const [key, value] of doc.getMap("meta").entries()) {
    meta[key] = value;
  }
  return { nodes, edges, ...meta };
}

const sameValue = (a: unknown, b: unknown) => stable(a) === stable(b);

function writeField(map: Y.Map<unknown>, entry: CanvasEntry, key: string): void {
  const value = entry[key];
  if (key === "text" && entry.type === "text" && typeof value === "string") {
    const current = map.get(key);
    if (current instanceof Y.Text) {
      applyTextChange(current, value);
    } else {
      map.set(key, new Y.Text(value));
    }
    return;
  }
  const current = map.get(key);
  if (!sameValue(current instanceof Y.Text ? current.toString() : current, value)) {
    map.set(key, value);
  }
}

function newEntry(entry: CanvasEntry): Y.Map<unknown> {
  const map = new Y.Map<unknown>();
  for (const key of Object.keys(entry)) {
    const value = entry[key];
    map.set(key, key === "text" && entry.type === "text" && typeof value === "string" ? new Y.Text(value) : value);
  }
  return map;
}

function applyEntries(entries: EntryMap, before: CanvasEntry[] | null, after: CanvasEntry[]): void {
  const prior = before ? new Map(before.map((entry) => [entry.id, entry])) : null;
  const wanted = new Map(after.map((entry) => [entry.id, entry]));
  if (prior) {
    for (const id of prior.keys()) {
      if (!wanted.has(id)) {
        entries.delete(id);
      }
    }
  }
  const created: string[] = [];
  for (const entry of after) {
    const existing = entries.get(entry.id);
    const was = prior?.get(entry.id);
    if (!existing) {
      if (!was) {
        entries.set(entry.id, newEntry(entry));
        created.push(entry.id);
      }
      continue;
    }
    if (!was) {
      continue;
    }
    for (const key of Object.keys(entry)) {
      if (!sameValue(was[key], entry[key])) {
        writeField(existing, entry, key);
      }
    }
    for (const key of Object.keys(was)) {
      if (!(key in entry)) {
        existing.delete(key);
      }
    }
  }
  placeInOrder(entries, after.map((entry) => entry.id).filter((id) => entries.has(id)), new Set(created), before !== null);
}

/** Haelt die Reihenfolge des Canvas ein: nur neue Eintraege bekommen eine Stelle, erst eine echte Umordnung schreibt alle neu. */
function placeInOrder(entries: EntryMap, desired: string[], created: Set<string>, mayReorder: boolean): void {
  const current = orderedIds(entries).filter((id) => desired.includes(id) && !created.has(id));
  const kept = desired.filter((id) => !created.has(id));
  const unchanged = !mayReorder || (current.length === kept.length && current.every((id, index) => id === kept[index]));
  const orderOf = (id: string) => entries.get(id)?.get(ORDER);
  if (!unchanged) {
    desired.forEach((id, index) => {
      if (orderOf(id) !== index) {
        entries.get(id)!.set(ORDER, index);
      }
    });
    return;
  }
  desired.forEach((id, index) => {
    if (!created.has(id)) {
      return;
    }
    const previous = desired.slice(0, index).reverse().map(orderOf).find((value) => typeof value === "number") as number | undefined;
    const next = desired.slice(index + 1).map(orderOf).find((value) => typeof value === "number") as number | undefined;
    const value = previous !== undefined && next !== undefined ? (previous + next) / 2
      : previous !== undefined ? previous + 1 : next !== undefined ? next - 1 : 0;
    entries.get(id)!.set(ORDER, value);
  });
}

/**
 * Uebertraegt die Aenderung `before` -> `after` ins Dokument - nur die Unterschiede, damit gleichzeitige
 * Aenderungen anderer Geraete an anderen Stellen bestehen bleiben. `before === null` ist der Erstkontakt
 * ohne gemeinsame Basis: es kommt nur hinzu, was dem Dokument fehlt; nichts wird ueberschrieben oder geloescht.
 */
export function applyCanvasChange(doc: Y.Doc, before: CanvasData | null, after: CanvasData, origin?: unknown): void {
  doc.transact(() => {
    applyEntries(entryMap(doc, "nodes"), before?.nodes ?? null, after.nodes);
    applyEntries(entryMap(doc, "edges"), before?.edges ?? null, after.edges);
    const meta = doc.getMap("meta");
    const { nodes: _n, edges: _e, ...afterMeta } = after;
    const { nodes: _bn, edges: _be, ...beforeMeta } = before ?? { nodes: [], edges: [] };
    for (const [key, value] of Object.entries(afterMeta)) {
      if (before ? !sameValue(beforeMeta[key], value) : !meta.has(key)) {
        meta.set(key, value);
      }
    }
    if (before) {
      for (const key of Object.keys(beforeMeta)) {
        if (!(key in afterMeta)) {
          meta.delete(key);
        }
      }
    }
  }, origin);
}
