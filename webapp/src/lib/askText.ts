import { marked } from "marked";
import DOMPurify from "dompurify";

const CITATION = /\[(\d{1,2}(?:\s*,\s*\d{1,2})*)\]/g;

/**
 * Antwort des Modells als sicheres HTML (ADR 0017): Markdown, Zitate `[n]` werden zu Links auf `#cite-n`.
 * Andere Links verlieren ihr Ziel, Bilder und Skripte fallen weg - nichts aus der Modellantwort lädt oder öffnet Fremdes.
 */
export function renderAnswer(text: string): string {
  const linked = text.replace(CITATION, (_, list: string) =>
    list.split(",").map(number => `[[${number.trim()}]](#cite-${number.trim()})`).join(""));
  return DOMPurify.sanitize(marked.parse(linked, { async: false }), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ["img", "video", "audio", "iframe", "style", "form", "input", "button"],
    FORBID_ATTR: ["style"],
    ALLOWED_URI_REGEXP: /^#cite-\d{1,2}$/,
  });
}

export { badge, badgeClass, isOpen, omittedText, statusText } from "./askStatus";
