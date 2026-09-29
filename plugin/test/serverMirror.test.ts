import { describe, expect, it } from "vitest";
import type { NoteListItem } from "../src/sync/NoteApiClient";
import { type FeedChanges, ServerMirror } from "../src/sync/serverMirror";

const entry = (id: string, path: string, revision = 1): NoteListItem => ({ id, vaultId: "v", path, noteLevel: 1, revision, kind: "NOTE" });
const page = (over: Partial<FeedChanges> = {}): FeedChanges => ({
  cursor: "20", more: false, entries: [], removed: [], folders: { changed: [], removed: [] }, relist: [], ...over,
});

describe("ServerMirror", () => {
  it("is only ready after a full load for this vault and these kinds", () => {
    const mirror = new ServerMirror();
    expect(mirror.isReadyFor("v", "note,file")).toBe(false);

    mirror.load("v", "note,file", "10", [entry("a", "A.md")], ["Team"]);

    expect(mirror.isReadyFor("v", "note,file")).toBe(true);
    expect(mirror.isReadyFor("v", "note")).toBe(false);
    expect(mirror.isReadyFor("w", "note,file")).toBe(false);
    expect(mirror.cursor).toBe("10");
  });

  it("applies changes: new and changed entries replace, removed ones go, folders follow", () => {
    const mirror = new ServerMirror();
    mirror.load("v", "note", "10", [entry("a", "A.md"), entry("b", "B.md")], ["Alt", "Team"]);

    mirror.apply(page({
      entries: [entry("a", "Team/A.md", 5), entry("c", "C.md")],
      removed: ["b", "unbekannt"],
      folders: { changed: ["Neu"], removed: ["Alt"] },
    }));

    expect(mirror.entries().map((e) => [e.id, e.path, e.revision])).toEqual([["a", "Team/A.md", 5], ["c", "C.md", 1]]);
    expect(mirror.folders()).toEqual(["Neu", "Team"]);
    expect(mirror.cursor).toBe("20");
  });

  it("stops being ready when the person's view changed somewhere, until reloaded", () => {
    const mirror = new ServerMirror();
    mirror.load("v", "note", "10", [entry("a", "A.md")], []);

    mirror.apply(page({ relist: ["Kunden"] }));

    expect(mirror.isReadyFor("v", "note")).toBe(false);
    mirror.load("v", "note", "30", [entry("a", "A.md")], []);
    expect(mirror.isReadyFor("v", "note")).toBe(true);
  });

  it("finds an entry by path", () => {
    const mirror = new ServerMirror();
    mirror.load("v", "note", "10", [entry("a", "Team/A.md")], []);

    expect(mirror.byPath("Team/A.md")?.id).toBe("a");
    expect(mirror.byPath("Team/B.md")).toBeUndefined();
  });

  it("keeps folders unknown when the server had no folder list", () => {
    const mirror = new ServerMirror();
    mirror.load("v", "note", "10", [], null);

    expect(mirror.folders()).toBeNull();
    mirror.apply(page({ folders: { changed: ["X"], removed: [] } }));
    expect(mirror.folders()).toBeNull();
  });
});
