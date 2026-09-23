import * as Y from "yjs";
import { applyTextChange } from "../../plugin/src/sync/textDiff";

/**
 * Yjs fuer die Java-API (ADR 0008): laeuft eingebettet ueber GraalJS in `platform-api`. Notizen
 * bleiben Yjs-Dokumente (ADR 0002) - Java liest ihren Text und haengt Aenderungen als minimale
 * Yjs-Updates an, mit exakt derselben Diff-Logik wie das Obsidian-Plugin (importiert, nicht
 * kopiert). Schnittstelle bewusst nur Strings (Base64), damit GraalJS keinen Java-Zugriff braucht.
 */

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const LOOKUP = new Uint8Array(128);
for (let i = 0; i < ALPHABET.length; i++) {
  LOOKUP[ALPHABET.charCodeAt(i)] = i;
}

/** GraalJS hat kein btoa/atob - eigene, kleine Base64-Umwandlung. */
export function bytesToBase64(bytes: Uint8Array): string {
  let out = "";
  for (let i = 0; i < bytes.length; i += 3) {
    const n = (bytes[i] << 16) | ((bytes[i + 1] ?? 0) << 8) | (bytes[i + 2] ?? 0);
    out += ALPHABET[(n >> 18) & 63] + ALPHABET[(n >> 12) & 63]
      + (i + 1 < bytes.length ? ALPHABET[(n >> 6) & 63] : "=")
      + (i + 2 < bytes.length ? ALPHABET[n & 63] : "=");
  }
  return out;
}

export function base64ToBytes(base64: string): Uint8Array {
  const clean = base64.replace(/=+$/, "");
  const out = new Uint8Array(Math.floor((clean.length * 3) / 4));
  let o = 0;
  for (let i = 0; i < clean.length; i += 4) {
    const n = (LOOKUP[clean.charCodeAt(i)] << 18) | (LOOKUP[clean.charCodeAt(i + 1)] << 12)
      | ((LOOKUP[clean.charCodeAt(i + 2)] ?? 0) << 6) | (LOOKUP[clean.charCodeAt(i + 3)] ?? 0);
    if (o < out.length) out[o++] = (n >> 16) & 255;
    if (o < out.length) out[o++] = (n >> 8) & 255;
    if (o < out.length) out[o++] = n & 255;
  }
  return out;
}

function load(updates: string[]): Y.Doc {
  const doc = new Y.Doc();
  for (const update of updates) {
    Y.applyUpdate(doc, base64ToBytes(update));
  }
  return doc;
}

/** Text einer Notiz aus ihrer Update-Historie. */
export function textOf(updates: string[]): string {
  const doc = load(updates);
  try {
    return doc.getText("content").toString();
  } finally {
    doc.destroy();
  }
}

/** Minimales Update von der Historie zum Zieltext; `null`, wenn der Text schon so ist. */
export function change(updates: string[], next: string): string | null {
  const doc = load(updates);
  try {
    const before = Y.encodeStateVector(doc);
    const text = doc.getText("content");
    if (text.toString() === next) {
      return null;
    }
    applyTextChange(text, next);
    return bytesToBase64(Y.encodeStateAsUpdate(doc, before));
  } finally {
    doc.destroy();
  }
}

(globalThis as unknown as { yjsBridge: unknown }).yjsBridge = { textOf, change };
