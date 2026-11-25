/**
 * Kommentartext mit Erwähnungen (ADR 0016): im Text steht `@[Name](subject)`, der Server leitet die
 * Erwähnten daraus ab. Rein und getestet; die Webapp nutzt eine exakte Kopie
 * (webapp/src/lib/commentText.ts, per Test abgesichert).
 */

export type Segment = { kind: "text"; text: string } | { kind: "mention"; name: string; subject: string };
export interface Member { subject: string; displayName?: string | null; }

const TOKEN = /@\[([^\]\n]{1,100})\]\(([^)\s]{1,200})\)/g;

/** Text und Erwähnungen getrennt - die Oberfläche setzt beides als Text, nie als HTML. */
export function segments(body: string): Segment[] {
  const result: Segment[] = [];
  let last = 0;
  for (const match of body.matchAll(TOKEN)) {
    if (match.index > last) result.push({ kind: "text", text: body.slice(last, match.index) });
    result.push({ kind: "mention", name: match[1], subject: match[2] });
    last = match.index + match[0].length;
  }
  if (last < body.length) result.push({ kind: "text", text: body.slice(last) });
  return result;
}

/** `null`, wenn sich das Subject nicht als Token schreiben lässt (Leerraum oder Klammer). */
export function mentionToken(name: string, subject: string): string | null {
  if (!subject || /[\s)]/.test(subject)) return null;
  const clean = name.replace(/[\]\r\n]/g, "").trim();
  return clean ? `@[${clean.slice(0, 100)}](${subject})` : null;
}

export function mentionedSubjects(body: string): string[] {
  return [...new Set(segments(body).flatMap((s) => (s.kind === "mention" ? [s.subject] : [])))];
}

/** Für Vorschau und Hinweise: `@[Ben](ben)` wird zu `@Ben`. */
export function previewText(body: string): string {
  return segments(body).map((s) => (s.kind === "mention" ? `@${s.name}` : s.text)).join("");
}

/** Das `@wort` vor dem Cursor, falls dort gerade eine Erwähnung getippt wird (höchstens zwei Wörter). */
export function activeMention(text: string, caret: number): { start: number; query: string } | null {
  const before = text.slice(0, caret);
  const start = before.lastIndexOf("@");
  if (start === -1 || (start > 0 && !/\s/.test(before[start - 1]))) return null;
  const query = before.slice(start + 1);
  if (query.length > 40 || /[\n[\]()]/.test(query) || query.split(" ").length > 2) return null;
  return { start, query };
}

export function suggest(members: Member[], query: string, self: string | null, limit = 6): Member[] {
  const needle = query.trim().toLowerCase();
  const name = (m: Member) => (m.displayName?.trim() || m.subject).toLowerCase();
  const others = members.filter((m) => m.subject !== self);
  if (!needle) return others.slice(0, limit);
  const starts = (m: Member) => name(m).split(/\s+/).some((word) => word.startsWith(needle)) || name(m).startsWith(needle);
  const first = others.filter(starts);
  const then = others.filter((m) => !starts(m) && (name(m).includes(needle) || m.subject.toLowerCase().includes(needle)));
  return [...first, ...then].slice(0, limit);
}

/** Ersetzt das getippte `@abc` durch das Token; `caret` liegt danach hinter dem Leerzeichen. */
export function insertMention(text: string, start: number, caret: number, name: string, subject: string): { text: string; caret: number } {
  const token = mentionToken(name, subject);
  if (!token) return { text, caret };
  const after = text.slice(caret);
  const spacer = after.startsWith(" ") ? "" : " ";
  return { text: text.slice(0, start) + token + spacer + after, caret: start + token.length + 1 };
}
