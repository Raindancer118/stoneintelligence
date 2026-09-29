import diff from "fast-diff";
import { describeActor, formatDate } from "./historyText";

/**
 * Versionsverlauf einer Notiz: Beschriftung und zeilenweiser Vergleich. Rein und getestet - die
 * Webapp nutzt eine exakte Kopie (webapp/src/lib/versionText.ts, per Test abgesichert).
 */

/** `actor === null`: geschrieben, bevor der Server Autoren je Aenderung festhielt. */
export interface NoteVersion {
  revision: number; firstRevision: number; actor: string | null;
  startedAt: string; endedAt: string; updates: number;
}
export interface VersionList { currentRevision: number; versions: NoteVersion[]; total: number; }
export interface VersionText {
  revision: number; at: string; actor: string | null; text: string; current: string; currentRevision: number;
}
export interface Restored { revision: number; changed: boolean; }

export interface DiffRow { kind: "same" | "added" | "removed" | "gap"; text: string; }

export function describeVersion(version: NoteVersion, timeZone?: string): { who: string; when: string } {
  return {
    who: version.actor === null ? "unbekannt" : describeActor(version.actor),
    when: formatDate(version.endedAt, timeZone),
  };
}

const CONTEXT = 3;

/** Zeilen von `before` nach `after`; lange unveraenderte Strecken werden zu einer "gap"-Zeile. */
export function lineDiff(before: string, after: string): DiffRow[] {
  if (before === after) {
    return [];
  }
  const a = before.split("\n");
  const b = after.split("\n");
  let head = 0;
  while (head < a.length && head < b.length && a[head] === b[head]) {
    head++;
  }
  let tail = 0;
  while (tail < a.length - head && tail < b.length - head && a[a.length - 1 - tail] === b[b.length - 1 - tail]) {
    tail++;
  }
  const rows: DiffRow[] = a.slice(0, head).map((text) => ({ kind: "same", text }));
  rows.push(...middle(a.slice(head, a.length - tail), b.slice(head, b.length - tail)));
  rows.push(...a.slice(a.length - tail).map((text): DiffRow => ({ kind: "same", text })));
  return fold(rows);
}

/**
 * Jede verschiedene Zeile wird zu einem Zeichen, dann vergleicht fast-diff die Zeichenketten -
 * so bleiben Zeilen ganz. Reichen die Zeichen nicht, wird der Block als ersetzt gezeigt.
 */
function middle(a: string[], b: string[]): DiffRow[] {
  const codes = new Map<string, string>();
  let next = 0x100;
  const encode = (lines: string[]): string | null => {
    let out = "";
    for (const line of lines) {
      let code = codes.get(line);
      if (code === undefined) {
        if (next === 0xd800) {
          next = 0xe000;
        }
        if (next > 0xffff) {
          return null;
        }
        code = String.fromCharCode(next++);
        codes.set(line, code);
      }
      out += code;
    }
    return out;
  };
  const left = encode(a);
  const right = left === null ? null : encode(b);
  if (left === null || right === null) {
    return [...a.map((text): DiffRow => ({ kind: "removed", text })), ...b.map((text): DiffRow => ({ kind: "added", text }))];
  }
  const lines = new Map([...codes].map(([line, code]) => [code, line]));
  const rows: DiffRow[] = [];
  for (const [op, chunk] of diff(left, right)) {
    const kind = op === diff.EQUAL ? "same" : op === diff.INSERT ? "added" : "removed";
    for (const code of chunk) {
      rows.push({ kind, text: lines.get(code) ?? "" });
    }
  }
  return rows;
}

function fold(rows: DiffRow[]): DiffRow[] {
  const changed = rows.map((row) => row.kind !== "same");
  const near = (index: number): boolean => {
    for (let i = Math.max(0, index - CONTEXT); i <= Math.min(rows.length - 1, index + CONTEXT); i++) {
      if (changed[i]) {
        return true;
      }
    }
    return false;
  };
  const out: DiffRow[] = [];
  let hidden = 0;
  rows.forEach((row, index) => {
    if (near(index)) {
      if (hidden > 0) {
        out.push(gap(hidden));
        hidden = 0;
      }
      out.push(row);
    } else {
      hidden++;
    }
  });
  if (hidden > 0) {
    out.push(gap(hidden));
  }
  return out;
}

function gap(count: number): DiffRow {
  return { kind: "gap", text: count === 1 ? "1 unveränderte Zeile" : `${count} unveränderte Zeilen` };
}

export function explainVersionError(status: number): string {
  switch (status) {
    case 403:
      return "Zum Wiederherstellen brauchst du Schreibrecht an dieser Notiz.";
    case 404:
      return "Diese Notiz oder Version gibt es auf dem Server nicht mehr.";
    case 409:
      return "Die Notiz ändert sich gerade laufend – versuch es gleich noch einmal.";
    default:
      return `Der Server lehnte ab (HTTP ${status}).`;
  }
}
