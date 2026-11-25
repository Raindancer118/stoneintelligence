/**
 * Texte zu "Frag den Vault" (ADR 0017), rein und getestet. Die Webapp nutzt eine exakte Kopie
 * (webapp/src/lib/askStatus.ts, per Test abgesichert) - Aenderungen immer hier machen und dorthin kopieren.
 */

/** Was die Statusanzeige einer Frage braucht (die vollen Typen von Plugin und Webapp erfuellen es). */
export interface AskLike {
  status: "PENDING" | "RUNNING" | "ANSWERED" | "NOT_ANSWERABLE" | "FAILED" | "CANCELLED";
  progress: string | null; percent: number | null; error: string | null; waitingForCapacity: boolean;
}

export function isOpen(question: AskLike): boolean {
  return question.status === "PENDING" || question.status === "RUNNING";
}

export function statusText(question: AskLike): string {
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
export function badge(question: AskLike): string {
  switch (question.status) {
    case "RUNNING": return "Wird beantwortet";
    case "FAILED": return "Fehlgeschlagen";
    default: return statusText(question);
  }
}

/** Passende Klasse der gemeinsamen Statusanzeige der Webapp. */
export function badgeClass(question: AskLike): string {
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
