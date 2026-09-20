import * as Y from "yjs";
import { marked } from "marked";
import DOMPurify from "dompurify";

export function decodeContent(updates: string[]): Y.Doc {
  const doc = new Y.Doc();
  try {
    for (const update of updates) Y.applyUpdate(doc, Uint8Array.from(atob(update), (c) => c.charCodeAt(0)));
    return doc;
  } catch (error) {
    doc.destroy();
    throw error;
  }
}

/** Builds a delta from the last confirmed state; a failed save never mutates that baseline. */
export function prepareUpdate(updates: string[], content: string): string {
  const doc = decodeContent(updates);
  try {
    const before = Y.encodeStateVector(doc);
    const text = doc.getText("content");
    doc.transact(() => { text.delete(0, text.length); text.insert(0, content); });
    const delta = Y.encodeStateAsUpdate(doc, before);
    // Avoid spreading large notes into a function's argument list.
    let binary = "";
    for (const byte of delta) binary += String.fromCharCode(byte);
    return btoa(binary);
  } finally { doc.destroy(); }
}

export function renderMarkdown(content: string): string {
  return DOMPurify.sanitize(marked.parse(content, { async: false }), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ["img", "video", "audio", "iframe", "style", "form", "input", "button"],
    FORBID_ATTR: ["style"],
    ALLOWED_URI_REGEXP: /^(?:(?:https?|mailto):|#)/i,
  });
}

export function normalizeNotePath(value: string): string {
  const path = value.trim();
  if (!path || path.length > 1024 || /[\\\x00-\x1f:*?"<>|]/.test(path)
      || path.split("/").some((part) => !part || part === "." || part === ".." || part.startsWith("."))) {
    throw new Error("Bitte einen gültigen Pfad angeben, zum Beispiel Projekte/Ideen.md.");
  }
  return /\.md$/i.test(path) ? path : `${path}.md`;
}

export function downloadNote(path: string, content: string): void {
  const url = URL.createObjectURL(new Blob([content], { type: "text/markdown;charset=utf-8" }));
  const link = document.createElement("a");
  link.href = url; link.download = path.split("/").pop() || "Notiz.md"; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
