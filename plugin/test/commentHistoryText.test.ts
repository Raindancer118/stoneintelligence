import { describe, expect, it } from "vitest";
import { describeEvent } from "../src/sync/historyText";
import type { HistoryEvent } from "../src/sync/historyText";

const event = (action: string): HistoryEvent => ({
  actor: "anna", action, payload: { threadId: "t", commentId: "c" }, occurredAt: "2026-11-25T10:00:00Z", noteId: "n1",
  path: "Team/plan.md", paths: ["Team/plan.md"],
});

describe("historyText for comments", () => {
  it("should_tellCommentEventsInPlainWords", () => {
    expect(describeEvent(event("comment.created"))).toBe("anna hat „plan“ kommentiert");
    expect(describeEvent(event("comment.edited"))).toBe("anna hat einen Kommentar zu „plan“ bearbeitet");
    expect(describeEvent(event("comment.deleted"))).toBe("anna hat einen Kommentar zu „plan“ gelöscht");
    expect(describeEvent(event("comment.resolved"))).toBe("anna hat eine Kommentar-Diskussion zu „plan“ gelöst");
    expect(describeEvent(event("comment.reopened"))).toBe("anna hat eine Kommentar-Diskussion zu „plan“ wieder geöffnet");
  });
});
