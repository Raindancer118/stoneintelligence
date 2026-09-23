import { describe, expect, it } from "vitest";
import { aiChangeSummary, orderForActiveFile, revertReportText } from "../src/ui/aiChanges";
import type { AiChangeSetView } from "../src/ui/aiChanges";

const set = (id: string, label: string, paths: string[], revertedAt: string | null = null): AiChangeSetView => ({
  changeSet: { id, service: "gateway", agent: "ki:AI Gateway", requestedBy: "tom", label, createdAt: "2026-09-23T10:00:00Z", revertedAt },
  changes: paths.map((path) => ({ noteId: path, path, kind: path.endsWith(".md") ? "CREATED" : "FILE_CREATED", at: "2026-09-23T10:00:00Z" })),
});

describe("aiChanges", () => {
  it("summarises what a run wrote, counting notes and files apart", () => {
    expect(aiChangeSummary(set("1", "Brief.pdf", ["Notizen/Unfall.md", "Notizen/Merle Wilkens.md", "Anhänge/Brief.pdf"])))
      .toBe("2 Notizen, 1 Datei · AI Gateway");
    expect(aiChangeSummary(set("1", "Brief.pdf", ["Notizen/Unfall.md"], "2026-09-23T11:00:00Z")))
      .toBe("1 Notiz · AI Gateway · rückgängig gemacht");
  });

  // Wer in einer KI-Notiz steht und „rückgängig“ will, meint den Lauf, der sie geschrieben hat.
  it("puts the runs that touched the open note first", () => {
    const sets = [set("a", "Alt.pdf", ["Notizen/X.md"]), set("b", "Brief.pdf", ["Notizen/Unfall.md"])];

    const ordered = orderForActiveFile(sets, "Notizen/Unfall.md");

    expect(ordered.map((view) => view.changeSet.id)).toEqual(["b", "a"]);
    expect(ordered[0].touchesActive).toBe(true);
    expect(ordered[1].touchesActive).toBe(false);
  });

  it("explains a partial undo instead of pretending it all went", () => {
    expect(revertReportText({ reverted: 3, conflicts: [] })).toBe("3 Änderungen rückgängig gemacht.");
    expect(revertReportText({ reverted: 1, conflicts: [{ path: "Notizen/Unfall.md", reason: "Seit der KI hat jemand weitergeschrieben" }] }))
      .toBe("1 Änderung rückgängig gemacht. Stehen geblieben: Notizen/Unfall.md (Seit der KI hat jemand weitergeschrieben).");
  });
});
