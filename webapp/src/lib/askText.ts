import { marked } from "marked";
import DOMPurify from "dompurify";
import type { AskQuestion } from "./api";

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

export function isOpen(question: AskQuestion): boolean {
  return question.status === "PENDING" || question.status === "RUNNING";
}

export function statusText(question: AskQuestion): string {
  switch (question.status) {
    case "PENDING": return question.waitingForCapacity ? "Wartet auf Kontingent" : "Wartet";
    case "RUNNING": {
      const parts = [question.progress, question.percent !== null ? `${question.percent} %` : null].filter(Boolean);
      return parts.length ? parts.join(" · ") : "Wird beantwortet";
    }
    case "ANSWERED": return "Beantwortet";
    case "NOT_ANSWERABLE": return "Nicht beantwortbar";
    case "FAILED": return question.error ? `Fehlgeschlagen: ${question.error}` : "Fehlgeschlagen";
    case "CANCELLED": return "Abgebrochen";
  }
}

/** Kurzes Etikett der Statusanzeige; Einzelheiten (Fortschritt, Fehler) liefert {@link statusText}. */
export function badge(question: AskQuestion): string {
  switch (question.status) {
    case "RUNNING": return "Wird beantwortet";
    case "FAILED": return "Fehlgeschlagen";
    default: return statusText(question);
  }
}

/** Passende Klasse der gemeinsamen Statusanzeige (app.css). */
export function badgeClass(question: AskQuestion): string {
  switch (question.status) {
    case "ANSWERED": return "status-succeeded";
    case "FAILED": return "status-failed";
    case "RUNNING": return "status-running";
    case "PENDING": return question.waitingForCapacity ? "status-capacity" : "status-pending";
    default: return "status-cancelled";
  }
}

/** Hinweis, wenn ein externer Dienst wegen fehlender Einwilligung nicht alle Notizen bekam; sonst `null`. */
export function omittedText(count: number): string | null {
  if (count <= 0) return null;
  return count === 1
    ? "Eine Notiz mit passenden Stellen wurde nicht berücksichtigt, weil ihr Verfasser einer Auswertung durch externe KI-Anbieter nicht zugestimmt hat."
    : `${count} Notizen mit passenden Stellen wurden nicht berücksichtigt, weil ihre Verfasser einer Auswertung durch externe KI-Anbieter nicht zugestimmt haben.`;
}
