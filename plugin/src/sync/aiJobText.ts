import type { AiJob, AiService } from "./NoteApiClient";

/** Reine Hilfen fuer "Mit KI einlesen" und die KI-Ansicht (ADR 0011). */

/** Was die Pipeline lesen kann (AiJobService.contentTypeOf): PDF und Text. Notizen selbst liest sie nicht ein. */
export function canReadWithAi(path: string): boolean {
  return /\.(pdf|txt|markdown)$/i.test(path);
}

/** Nur Dienste, die JEDES der gewaehlten Level verarbeiten duerfen. */
export function servicesFor(services: AiService[], levels: number[]): AiService[] {
  return services.filter((service) => levels.every((level) => service.levels.includes(level)));
}

export function canCancel(job: AiJob): boolean {
  return job.status === "PENDING" || job.status === "RUNNING";
}

export function jobStatusText(job: AiJob, timeZone?: string): string {
  switch (job.status) {
    case "RUNNING":
      return `Läuft${job.percent !== null ? ` (${job.percent} %)` : ""}${job.progress ? ` – ${job.progress}` : ""}`;
    case "PENDING":
      if (job.waitingForCapacity && job.availableAt) {
        const time = new Intl.DateTimeFormat("de-DE", { hour: "2-digit", minute: "2-digit", ...(timeZone ? { timeZone } : {}) })
          .format(new Date(job.availableAt));
        return `Wartet auf KI-Kontingent bis ${time}`;
      }
      return "Wartet";
    case "SUCCEEDED":
      return `Fertig${job.progress ? ` – ${job.progress}` : ""}`;
    case "FAILED":
      return `Fehlgeschlagen${job.error ? ` – ${job.error}` : ""}`;
    default:
      return "Abgebrochen";
  }
}
