import { describe, expect, it } from "vitest";
import { activityLines, describeActor, describeEvent } from "../src/sync/historyText";
import type { HistoryEvent } from "../src/sync/NoteApiClient";

const event = (action: string, payload: Record<string, unknown> = {}, actor = "tom", path: string | null = "Team/plan.md"): HistoryEvent => ({
  actor, action, payload, occurredAt: "2026-09-26T10:00:00Z", noteId: "n1", path, paths: path ? [path] : [],
});

describe("historyText", () => {
  it("should_tellNotesAndFilesInPlainWords", () => {
    expect(describeEvent(event("note.created", { path: "Team/plan.md" }))).toBe("tom hat „plan“ angelegt");
    expect(describeEvent(event("note.content-updated"))).toBe("tom hat „plan“ bearbeitet");
    expect(describeEvent(event("note.renamed", { from: "Team/plan.md", to: "Team/Plan 2.md" }))).toBe("tom hat „plan“ in „Plan 2“ umbenannt");
    expect(describeEvent(event("note.renamed", { from: "Team/plan.md", to: "Archiv/plan.md" }))).toBe("tom hat „plan“ nach „Archiv“ verschoben");
    expect(describeEvent(event("note.deleted"))).toBe("tom hat „plan“ gelöscht");
    expect(describeEvent(event("file.content-updated", {}, "ben", "Anhänge/Skript.pdf"))).toBe("ben hat eine neue Fassung von „Skript.pdf“ hochgeladen");
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
