import { describe, expect, it } from "vitest";
import { isSyncableFilePath, planFiles, type FilePlanInput } from "../src/sync/filePlan";

function input(overrides: Partial<FilePlanInput>): FilePlanInput {
  return {
    serverFiles: [], localFiles: [], fileIds: {}, meta: {}, pendingIds: new Set(), blockedPaths: {}, maxFileBytes: 1000,
    ...overrides,
  };
}
const local = (path: string, mtime = 1, size = 10) => ({ path, mtime, size });
const server = (id: string, path: string, revision = 1, sha256: string | null = "h1") => ({ id, path, revision, sha256 });
const meta = (revision = 1, mtime = 1, size = 10, sha256 = "h1") => ({ revision, sha256, mtime, size });

describe("planFiles", () => {
  it("should_downloadServerFiles_thatAreMissingHere", () => {
    expect(planFiles(input({ serverFiles: [server("f1", "a.pdf", 2)] }))).toEqual([{ kind: "download", id: "f1", path: "a.pdf" }]);
  });

  // Der anlegende Rechner laedt den Inhalt erst nach dem Anlegen hoch - bis dahin nichts herunterladen.
  it("should_waitForTheContent_ofAFileThatHasNoneYet", () => {
    expect(planFiles(input({ serverFiles: [server("f1", "a.pdf", 0, null)] }))).toEqual([]);
  });

  it("should_createFilesThatExistOnlyHere", () => {
    expect(planFiles(input({ localFiles: [local("Bilder/x.png")] }))).toEqual([{ kind: "create", path: "Bilder/x.png" }]);
  });

  it("should_uploadLocalChanges_onTheLastKnownRevision", () => {
    const plan = planFiles(input({
      serverFiles: [server("f1", "a.pdf", 3)], localFiles: [local("a.pdf", 5, 12)],
      fileIds: { "a.pdf": "f1" }, meta: { f1: meta(3) },
    }));

    expect(plan).toEqual([{ kind: "upload", id: "f1", path: "a.pdf", baseRevision: 3 }]);
  });

  it("should_downloadNewerServerVersions_whenNothingChangedHere", () => {
    const plan = planFiles(input({
      serverFiles: [server("f1", "a.pdf", 4, "h2")], localFiles: [local("a.pdf")], fileIds: { "a.pdf": "f1" }, meta: { f1: meta(3) },
    }));

    expect(plan).toEqual([{ kind: "download", id: "f1", path: "a.pdf" }]);
  });

  // Binaerdateien lassen sich nicht zusammenfuehren - die Pruefung am Inhalt macht der Ausfuehrende.
  it("should_markChangesOnBothSides_asAConflict", () => {
    const plan = planFiles(input({
      serverFiles: [server("f1", "a.pdf", 4, "h2")], localFiles: [local("a.pdf", 9, 99)], fileIds: { "a.pdf": "f1" }, meta: { f1: meta(3) },
    }));

    expect(plan).toEqual([{ kind: "conflict", id: "f1", path: "a.pdf" }]);
  });

  it("should_doNothing_whenBothSidesAreUnchanged", () => {
    expect(planFiles(input({
      serverFiles: [server("f1", "a.pdf", 3)], localFiles: [local("a.pdf")], fileIds: { "a.pdf": "f1" }, meta: { f1: meta(3) },
    }))).toEqual([]);
  });

  it("should_followRenamesFromOtherDevices", () => {
    expect(planFiles(input({
      serverFiles: [server("f1", "Archiv/a.pdf", 3)], localFiles: [local("a.pdf")], fileIds: { "a.pdf": "f1" }, meta: { f1: meta(3) },
    }))).toEqual([{ kind: "renameLocal", id: "f1", from: "a.pdf", to: "Archiv/a.pdf" }]);
  });

  // Wurde die Datei schon an den neuen Ort verschoben (z. B. unterbrochener Abgleich), nur neu zuordnen.
  it("should_relinkAFileThatAlreadyLivesAtTheServerPath", () => {
    expect(planFiles(input({
      serverFiles: [server("f1", "Archiv/a.pdf", 3)], localFiles: [local("Archiv/a.pdf")], fileIds: { "a.pdf": "f1" }, meta: { f1: meta(3) },
    }))).toEqual([{ kind: "adopt", id: "f1", path: "Archiv/a.pdf" }]);
  });

  it("should_askWhetherAFileMissingOnTheServerWasDeleted", () => {
    expect(planFiles(input({ localFiles: [local("a.pdf")], fileIds: { "a.pdf": "f1" }, meta: { f1: meta(3) } })))
      .toEqual([{ kind: "checkMissing", id: "f1", path: "a.pdf" }]);
  });

  // Gleicher Pfad hier und auf dem Server, noch nicht verknuepft: am Inhalt entscheiden (Ausfuehrender).
  it("should_adoptAServerFile_withTheSamePath", () => {
    expect(planFiles(input({ serverFiles: [server("f1", "a.pdf", 2)], localFiles: [local("a.pdf")] })))
      .toEqual([{ kind: "adopt", id: "f1", path: "a.pdf" }]);
  });

  it("should_leaveFilesAlone_thatAQueuedOperationIsAbout_orThatAreBlocked", () => {
    expect(planFiles(input({
      serverFiles: [server("f1", "a.pdf", 5)], localFiles: [local("a.pdf", 7), local("gesperrt.png")],
      fileIds: { "a.pdf": "f1" }, meta: { f1: meta(3) }, pendingIds: new Set(["f1"]), blockedPaths: { "gesperrt.png": "Keine Berechtigung" },
    }))).toEqual([]);
  });

  it("should_reportFilesAboveTheLimit_insteadOfUploadingThem", () => {
    expect(planFiles(input({ localFiles: [local("film.mp4", 1, 5000)] }))).toEqual([{ kind: "tooLarge", path: "film.mp4", size: 5000 }]);
  });

  it("should_neverTouchNotesOrHiddenFiles", () => {
    expect(planFiles(input({ localFiles: [local("Notiz.md"), local(".obsidian/workspace.json"), local("a/.DS_Store")] }))).toEqual([]);
  });
});

describe("isSyncableFilePath", () => {
  it("should_matchTheServerRules", () => {
    expect(["a.pdf", "Bilder/Ü ber.png", "ohne-endung"].every(isSyncableFilePath)).toBe(true);
    expect(["a.md", "A.MD", ".obsidian/x.json", "a/.trash/b.png", "a:b.png", "", "a//b.png"].some(isSyncableFilePath)).toBe(false);
  });
});
