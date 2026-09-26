import { describe, expect, it } from "vitest";
import { canCancel, canReadWithAi, jobStatusText, servicesFor } from "../src/sync/aiJobText";
import type { AiJob } from "../src/sync/NoteApiClient";

const job = (overrides: Partial<AiJob>): AiJob => ({
  id: "j", service: "gateway", requestedBy: "tom", fileName: "Skript.pdf", size: 1, level: 1, status: "RUNNING",
  progress: "Abschnitt 3/12 gelesen", percent: 40, error: null, changeSetId: null, createdAt: "2026-09-26T10:00:00Z",
  finishedAt: null, availableAt: null, waitingForCapacity: false, ...overrides,
});

describe("aiJobText", () => {
  it("should_offerAiOnlyForFilesThePipelineCanRead", () => {
    expect(canReadWithAi("Anhänge/Skript.pdf")).toBe(true);
    expect(canReadWithAi("liesmich.TXT")).toBe(true);
    expect(canReadWithAi("Bild.png")).toBe(false);
    expect(canReadWithAi("Notiz.md")).toBe(false);
  });

  it("should_offerOnlyServicesThatMayProcessTheLevel", () => {
    const services = [{ id: "a", name: "Extern", levels: [1] }, { id: "b", name: "Lokal", levels: [1, 2] }];
    expect(servicesFor(services, [2]).map((s) => s.id)).toEqual(["b"]);
    expect(servicesFor(services, [1, 1]).map((s) => s.id)).toEqual(["a", "b"]);
  });

  it("should_describeEveryState", () => {
    expect(jobStatusText(job({}))).toBe("Läuft (40 %) – Abschnitt 3/12 gelesen");
    expect(jobStatusText(job({ status: "PENDING", waitingForCapacity: true, availableAt: "2026-09-26T12:00:00Z" }), "UTC"))
      .toBe("Wartet auf KI-Kontingent bis 12:00");
    expect(jobStatusText(job({ status: "PENDING" }))).toBe("Wartet");
    expect(jobStatusText(job({ status: "SUCCEEDED", progress: "4 Notizen geschrieben" }))).toBe("Fertig – 4 Notizen geschrieben");
    expect(jobStatusText(job({ status: "FAILED", error: "PDF ohne Text" }))).toBe("Fehlgeschlagen – PDF ohne Text");
    expect(jobStatusText(job({ status: "CANCELLED" }))).toBe("Abgebrochen");
  });

  it("should_allowCancelling_onlyWhileWaitingOrRunning", () => {
    expect(canCancel(job({ status: "RUNNING" }))).toBe(true);
    expect(canCancel(job({ status: "PENDING" }))).toBe(true);
    expect(canCancel(job({ status: "SUCCEEDED" }))).toBe(false);
  });
});
