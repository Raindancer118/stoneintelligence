/** Fundstelle vom Server: Treffer stehen zwischen U+0002 und U+0003 (keine HTML-Markierung, kein {@html}). */
export function excerptParts(text: string): { text: string; hit: boolean }[] {
  const parts: { text: string; hit: boolean }[] = [];
  const push = (chunk: string, hit: boolean) => { if (chunk) parts.push({ text: chunk, hit }); };
  let rest = text;
  for (;;) {
    const start = rest.indexOf("\u0002");
    if (start < 0) { push(rest, false); return parts; }
    push(rest.slice(0, start), false);
    const end = rest.indexOf("\u0003", start + 1);
    if (end < 0) { push(rest.slice(start + 1), true); return parts; }
    push(rest.slice(start + 1, end), true);
    rest = rest.slice(end + 1);
  }
}
