import { describe, expect, it } from "vitest";
import { explainCommentError, noticeText, summaryText, unseenMentions } from "../src/ui/mentionNotice";
import { type CommentNotification, HttpError } from "../src/sync/NoteApiClient";

describe("explainCommentError", () => {
  it("should_showTheServersReasonForRefusedMentions", () => {
    expect(explainCommentError(new HttpError(422, "x", "Ben darf diese Notiz nicht lesen."))).toBe("Ben darf diese Notiz nicht lesen.");
  });

  it("should_explainOtherStatusesInOneSentence", () => {
    expect(explainCommentError(new HttpError(403, "x"))).toContain("keine Berechtigung");
    expect(explainCommentError(new HttpError(404, "x"))).toContain("nicht mehr");
    expect(explainCommentError(new Error("offline"))).toBe("offline");
  });
});

const item = (id: string, over: Partial<CommentNotification> = {}): CommentNotification => ({
  id, vaultId: "v", vaultName: "Team", noteId: "n", notePath: "Projekte/Plan.md", threadId: "t", commentId: "c",
  actorName: "Anna Adler", excerpt: "Schau mal @Tom", createdAt: "2026-11-25T09:00:00Z", read: false, ...over,
});

describe("mention notices", () => {
  it("should_announceOnlyUnreadMentionsNotShownBefore", () => {
    const seen = new Set(["a"]);

    expect(unseenMentions([item("a"), item("b"), item("c", { read: true })], seen).map((i) => i.id)).toEqual(["b"]);
  });

  it("should_writeTheNoticeInPlainWords", () => {
    expect(noticeText(item("1"))).toBe("Anna Adler hat dich in „Plan“ erwähnt: Schau mal @Tom");
  });

  it("should_cutLongExcerpts", () => {
    expect(noticeText(item("1", { excerpt: "x".repeat(200) })).length).toBeLessThan(150);
  });

  it("should_summarizeABurst", () => {
    expect(summaryText([item("1"), item("2"), item("3"), item("4")])).toBe("4 neue Erwähnungen. „Hinweise anzeigen“ in der Befehlspalette öffnet sie.");
    expect(summaryText([item("1")])).toBe("1 neue Erwähnung. „Hinweise anzeigen“ in der Befehlspalette öffnet sie.");
  });
});
