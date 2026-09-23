import { describe, expect, it } from "vitest";
import { isSyncablePath, planReconciliation, type ReconcileInput } from "../src/sync/reconcilePlan";

function input(overrides: Partial<ReconcileInput>): ReconcileInput {
  return {
    serverNotes: [],
    localFiles: [],
    noteIds: {},
    meta: {},
    pendingNoteIds: new Set(),
    livePaths: new Set(),
    blockedPaths: {},
    ...overrides,
  };
}

const file = (path: string, mtime = 1, size = 10) => ({ path, mtime, size });

describe("planReconciliation", () => {
  it("should_downloadServerNotes_thatAreMissingLocally", () => {
    const plan = planReconciliation(input({ serverNotes: [{ id: "n1", path: "a.md", revision: 3 }] }));

    expect(plan).toEqual([{ kind: "download", noteId: "n1", path: "a.md" }]);
  });

  it("should_uploadLocalNotes_thatTheServerDoesNotKnow", () => {
    const plan = planReconciliation(input({ localFiles: [file("neu.md")] }));

    expect(plan).toEqual([{ kind: "upload", path: "neu.md" }]);
  });

  // Zweites Geraet mit einer Kopie desselben Vaults: dieselbe Datei existiert lokal UND auf dem
  // Server, lokal aber noch ohne Zuordnung. Frueher wurde hier eine zweite Note angelegt (HTTP 409).
  it("should_adoptTheServerNote_when_anUnmappedLocalFileHasTheSamePath", () => {
    const plan = planReconciliation(input({
      serverNotes: [{ id: "n1", path: "gleich.md", revision: 2 }],
      localFiles: [file("gleich.md")],
    }));

    expect(plan).toEqual([{ kind: "adopt", noteId: "n1", path: "gleich.md" }]);
  });

  it("should_skipContentSync_when_neitherServerRevisionNorLocalFileChanged", () => {
    const plan = planReconciliation(input({
      serverNotes: [{ id: "n1", path: "a.md", revision: 4 }],
      localFiles: [file("a.md", 100, 20)],
      noteIds: { "a.md": "n1" },
      meta: { n1: { revision: 4, mtime: 100, size: 20 } },
    }));

    expect(plan).toEqual([]);
  });

  it("should_syncContent_when_theServerRevisionMoved", () => {
    const plan = planReconciliation(input({
      serverNotes: [{ id: "n1", path: "a.md", revision: 5 }],
      localFiles: [file("a.md", 100, 20)],
      noteIds: { "a.md": "n1" },
      meta: { n1: { revision: 4, mtime: 100, size: 20 } },
    }));

    expect(plan).toEqual([{ kind: "sync", noteId: "n1", path: "a.md", serverRevision: 5 }]);
  });

  it("should_syncContent_when_theLocalFileChangedSinceTheLastSync", () => {
    const plan = planReconciliation(input({
      serverNotes: [{ id: "n1", path: "a.md", revision: 4 }],
      localFiles: [file("a.md", 200, 20)],
      noteIds: { "a.md": "n1" },
      meta: { n1: { revision: 4, mtime: 100, size: 20 } },
    }));

    expect(plan).toEqual([{ kind: "sync", noteId: "n1", path: "a.md", serverRevision: 4 }]);
  });

  it("should_alwaysSync_when_theServerReportsNoRevision_olderServer", () => {
    const plan = planReconciliation(input({
      serverNotes: [{ id: "n1", path: "a.md" }],
      localFiles: [file("a.md", 100, 20)],
      noteIds: { "a.md": "n1" },
      meta: { n1: { revision: 4, mtime: 100, size: 20 } },
    }));

    expect(plan).toEqual([{ kind: "sync", noteId: "n1", path: "a.md", serverRevision: null }]);
  });

  it("should_leaveOpenNotes_toTheLiveBinding", () => {
    const plan = planReconciliation(input({
      serverNotes: [{ id: "n1", path: "a.md", revision: 9 }],
      localFiles: [file("a.md")],
      noteIds: { "a.md": "n1" },
      livePaths: new Set(["a.md"]),
    }));

    expect(plan).toEqual([]);
  });

  it("should_followARemoteRename_thatWasMissedWhileOffline", () => {
    const plan = planReconciliation(input({
      serverNotes: [{ id: "n1", path: "ordner/neu.md", revision: 1 }],
      localFiles: [file("alt.md")],
      noteIds: { "alt.md": "n1" },
      meta: { n1: { revision: 1, mtime: 1, size: 10 } },
    }));

    expect(plan).toEqual([{ kind: "renameLocal", noteId: "n1", from: "alt.md", to: "ordner/neu.md" }]);
  });

  it("should_notRenameOntoAnExistingLocalFile", () => {
    const plan = planReconciliation(input({
      serverNotes: [{ id: "n1", path: "b.md", revision: 1 }],
      localFiles: [file("a.md"), file("b.md")],
      noteIds: { "a.md": "n1" },
      meta: { n1: { revision: 1, mtime: 1, size: 10 } },
    }));

    expect(plan.filter((action) => action.kind === "renameLocal")).toEqual([]);
  });

  it("should_askTheServer_when_aKnownNoteIsMissingFromTheList", () => {
    const plan = planReconciliation(input({
      localFiles: [file("weg.md", 1, 10)],
      noteIds: { "weg.md": "n1" },
      meta: { n1: { revision: 1, mtime: 1, size: 10 } },
    }));

    expect(plan).toEqual([{ kind: "checkMissing", noteId: "n1", path: "weg.md" }]);
  });

  it("should_restoreAKnownNote_whoseLocalFileVanishedWithoutARecordedDelete", () => {
    const plan = planReconciliation(input({
      serverNotes: [{ id: "n1", path: "a.md", revision: 1 }],
      noteIds: { "a.md": "n1" },
    }));

    expect(plan).toEqual([{ kind: "download", noteId: "n1", path: "a.md" }]);
  });

  // Eine offline ausgefuehrte Loeschung/Umbenennung liegt noch in der Warteschlange: der Server
  // kennt sie noch nicht. Ohne diese Ausnahme wuerde der Abgleich sie rueckgaengig machen.
  it("should_leaveNotesWithPendingOperations_alone", () => {
    const plan = planReconciliation(input({
      serverNotes: [{ id: "n1", path: "alt.md", revision: 1 }],
      localFiles: [file("neu.md")],
      noteIds: { "neu.md": "n1" },
      pendingNoteIds: new Set(["n1"]),
    }));

    expect(plan).toEqual([]);
  });

  it("should_skipBlockedAndHiddenPaths", () => {
    const plan = planReconciliation(input({
      localFiles: [file("gesperrt.md"), file(".versteckt/a.md"), file("ordner/.b.md")],
      blockedPaths: { "gesperrt.md": "HTTP 403" },
    }));

    expect(plan).toEqual([]);
  });

  it("should_notDownloadIntoAPath_thatAnotherKnownNoteOccupies", () => {
    const plan = planReconciliation(input({
      serverNotes: [{ id: "n1", path: "a.md", revision: 1 }, { id: "n2", path: "a-alt.md", revision: 1 }],
      localFiles: [file("a.md")],
      noteIds: { "a.md": "n2" },
      meta: { n2: { revision: 1, mtime: 1, size: 10 } },
    }));

    expect(plan.some((action) => "noteId" in action && action.noteId === "n1")).toBe(false);
  });
});

describe("isSyncablePath", () => {
  it("should_acceptOrdinaryMarkdownPaths", () => {
    expect(isSyncablePath("Ordner/Unterordner/Notiz mit Leerzeichen.md")).toBe(true);
  });

  it("should_rejectPathsTheServerWouldRefuse", () => {
    for (const path of [".obsidian/x.md", "a/.hidden.md", "bild.png", "frage?.md", "a:b.md"]) {
      expect(isSyncablePath(path), path).toBe(false);
    }
  });
});
