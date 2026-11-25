import { describe, expect, it } from "vitest";
import { activityLines, describeActor, describeEvent, retentionLabel } from "../src/sync/historyText";
import type { HistoryEvent } from "../src/sync/historyText";

const event = (action: string, payload: Record<string, unknown> = {}, actor = "tom", path: string | null = "Team/plan.md"): HistoryEvent => ({
  actor, action, payload, occurredAt: "2026-09-26T10:00:00Z", noteId: "n1", path, paths: path ? [path] : [],
});

describe("historyText", () => {
  it("should_tellNotesAndFilesInPlainWords", () => {
    expect(describeEvent(event("note.created", { path: "Team/plan.md" }))).toBe("tom hat „plan“ angelegt");
    expect(describeEvent(event("note.content-updated"))).toBe("tom hat „plan“ bearbeitet");
    expect(describeEvent(event("note.version-restored", { restoredRevision: 3 }))).toBe("tom hat eine frühere Version von „plan“ wiederhergestellt");
    expect(describeEvent(event("note.renamed", { from: "Team/plan.md", to: "Team/Plan 2.md" }))).toBe("tom hat „plan“ in „Plan 2“ umbenannt");
    expect(describeEvent(event("note.renamed", { from: "Team/plan.md", to: "Archiv/plan.md" }))).toBe("tom hat „plan“ nach „Archiv“ verschoben");
    expect(describeEvent(event("note.deleted"))).toBe("tom hat „plan“ gelöscht");
    expect(describeEvent(event("file.content-updated", {}, "ben", "Anhänge/Skript.pdf"))).toBe("ben hat eine neue Fassung von „Skript.pdf“ hochgeladen");
  });

  it("should_tellRetentionChangesAndPurges", () => {
    expect(describeEvent(event("audit.retention-changed", { from: 365, to: 1095 }, "tom", null))).toBe("tom hat die Aufbewahrung des Protokolls auf 3 Jahre gestellt");
    expect(describeEvent(event("audit.retention-changed", { from: 365 }, "tom", null))).toBe("tom hat die Aufbewahrung des Protokolls auf unbegrenzt gestellt");
    expect(describeEvent(event("audit.purged", { count: 12, retentionDays: 730 }, "system", null))).toBe("Das Protokoll wurde bereinigt: 12 Einträge älter als 2 Jahre gelöscht");
    expect(describeEvent(event("audit.purged", { count: 1, retentionDays: 400 }, "system", null))).toBe("Das Protokoll wurde bereinigt: 1 Eintrag älter als 400 Tage gelöscht");
  });

  it("should_labelRetentionPeriods", () => {
    expect([null, 365, 730, 3650, 400].map(retentionLabel)).toEqual(["unbegrenzt", "1 Jahr", "2 Jahre", "10 Jahre", "400 Tage"]);
  });

  it("should_describeAQuestionToTheVault_withoutItsText", () => {
    expect(describeEvent(event("ai.ask", { service: "gemini", local: false, notes: ["a", "b"], omittedForConsent: 1 }, "ben", null)))
      .toBe("ben hat eine Frage an den Vault gestellt (2 Notizen an „gemini“ gesendet, 1 ohne Einwilligung ausgelassen)");
    expect(describeEvent(event("ai.ask", { service: "lokal", local: true, notes: ["a"], omittedForConsent: 0 }, "ben", null)))
      .toBe("ben hat eine Frage an den Vault gestellt (1 Notiz an „lokal“ gesendet)");
    expect(describeEvent(event("ai.ask", { service: "lokal", notes: [] }, "ben", null)))
      .toBe("ben hat eine Frage an den Vault gestellt (keine Notizen gesendet)");
  });

  it("should_nameTheAiAsSuch", () => {
    expect(describeActor("ki:AI Gateway")).toBe("KI „AI Gateway“");
    expect(describeEvent(event("note.created", {}, "ki:AI Gateway"))).toBe("KI „AI Gateway“ hat „plan“ angelegt");
  });

  it("should_describeSharing_andMembership", () => {
    expect(describeEvent(event("ACCESS_GRANTED", { target: "folder", path: "Team", scopeType: "USER", subject: "ben", permissions: ["READ"] }, "tom", "Team")))
      .toBe("tom hat für ben im Ordner „Team“ Lesen festgelegt");
    expect(describeEvent(event("ACCESS_GRANTED", { target: "folder", path: "", scopeType: "EVERYONE", subject: null, permissions: [] }, "tom", "")))
      .toBe("tom hat für alle Mitglieder im ganzen Vault Kein Zugriff festgelegt");
    expect(describeEvent(event("ACCESS_REVOKED", { target: "entry", path: "Team/plan.md", scopeType: "GROUP", subject: "g" })))
      .toBe("tom hat die Freigabe für eine Gruppe bei „plan“ entfernt");
    expect(describeEvent(event("MEMBER_REMOVED", { subject: "ben" }, "tom", null))).toBe("tom hat ben aus dem Vault genommen");
    expect(describeEvent(event("MEMBER_REMOVED", { subject: "ben" }, "ben", null))).toBe("ben hat den Vault verlassen");
    expect(describeEvent(event("SOMETHING_NEW", {}, "tom", null))).toBe("tom: SOMETHING_NEW");
  });

  it("should_summariseWhoCreatedEditedAndOpened", () => {
    const lines = activityLines({
      createdBy: "tom", createdAt: "2026-09-20T08:00:00Z", lastEditedBy: "anna", lastEditedAt: "2026-09-26T10:30:00Z",
      lastOpenedBy: null, lastOpenedAt: null,
    }, "UTC");

    expect(lines).toEqual([
      "Angelegt von tom am 20. September 2026, 08:00",
      "Zuletzt bearbeitet von anna am 26. September 2026, 10:30",
      "Noch von niemandem geöffnet",
    ]);
  });
});
