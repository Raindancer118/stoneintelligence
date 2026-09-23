import { describe, expect, it } from "vitest";
import { isSyncableFolderPath, planFolders, type FolderPlanInput } from "../src/sync/folderPlan";

function input(overrides: Partial<FolderPlanInput>): FolderPlanInput {
  return { serverFolders: [], knownFolders: [], localFolders: [], busyPaths: [], ...overrides };
}

const folder = (path: string, hasFiles = false) => ({ path, hasFiles });

describe("planFolders", () => {
  it("should_createServerFoldersLocally_evenEmptyOnes_parentsFirst", () => {
    const plan = planFolders(input({ serverFolders: ["A/B", "A", "C"] }));

    expect(plan.createLocal).toEqual(["A", "C", "A/B"]);
  });

  it("should_uploadFoldersCreatedHere", () => {
    const plan = planFolders(input({ localFolders: [folder("Neu")] }));

    expect(plan.upload).toEqual(["Neu"]);
    expect(plan.known).toEqual(["Neu"]);
  });

  // Toms Fund: ein anderswo geloeschter Ordner blieb hier leer liegen.
  it("should_removeFoldersDeletedElsewhere_onceTheyAreEmpty_deepestFirst", () => {
    const plan = planFolders(input({
      knownFolders: ["Projekt", "Projekt/Unter", "Bleibt"],
      serverFolders: ["Bleibt"],
      localFolders: [folder("Projekt"), folder("Projekt/Unter"), folder("Bleibt")],
    }));

    expect(plan.removeLocal).toEqual(["Projekt/Unter", "Projekt"]);
    expect(plan.upload).toEqual([]);
    expect(plan.known).toEqual(["Bleibt"]);
  });

  // Die Notiz-Loeschungen kommen einzeln an - bis dahin liegen noch Dateien im Ordner. Und was
  // hier nicht synchronisiert wird (Anhaenge, behaltene Notizen), darf nie mit geloescht werden.
  it("should_waitWhileADeletedFolderStillHoldsFiles_withoutResurrectingIt", () => {
    const plan = planFolders(input({
      knownFolders: ["Projekt", "Projekt/Bilder"],
      serverFolders: [],
      localFolders: [folder("Projekt", true), folder("Projekt/Bilder", true)],
    }));

    expect(plan.removeLocal).toEqual([]);
    expect(plan.upload).toEqual([]);
    expect(plan.known).toEqual(["Projekt", "Projekt/Bilder"]);
  });

  it("should_keepAParent_whoseEmptySubfolderStillExistsOnTheServer", () => {
    const plan = planFolders(input({
      knownFolders: ["A", "A/B"],
      serverFolders: ["A/B"],
      localFolders: [folder("A"), folder("A/B")],
    }));

    expect(plan.removeLocal).toEqual([]);
  });

  // Vor diesem Update blieben geloeschte Ordner leer liegen. Beim ersten Ordnerabgleich wuerden
  // sie sonst als "neu hier" an alle verteilt; mit Inhalt werden sie dagegen hochgeladen.
  it("should_onFirstSync_uploadFoldersWithContent_andClearEmptyLeftovers", () => {
    const plan = planFolders(input({
      knownFolders: null,
      serverFolders: ["Da"],
      localFolders: [folder("Da"), folder("Voll", true), folder("Rest"), folder("Rest/Leer")],
    }));

    expect(plan.upload).toEqual(["Voll"]);
    expect(plan.removeLocal).toEqual(["Rest/Leer", "Rest"]);
    expect(plan.known).toEqual(["Da", "Voll"]);
  });

  it("should_leaveFoldersAlone_thatAQueuedOperationIsAbout", () => {
    const plan = planFolders(input({
      knownFolders: ["Alt", "Alt/X"],
      serverFolders: ["Alt", "Alt/X"],
      localFolders: [folder("Neu"), folder("Neu/X")],
      busyPaths: ["Alt", "Neu"],
    }));

    expect(plan).toEqual({ createLocal: [], upload: [], removeLocal: [], known: ["Alt", "Alt/X"] });
  });
});

describe("isSyncableFolderPath", () => {
  it("should_matchTheServerRules", () => {
    expect(["A", "A/B c", "Ä/ö"].every(isSyncableFolderPath)).toBe(true);
    expect(["", "/", ".obsidian", "A/.trash", "A//B", " A", "A:B"].some(isSyncableFolderPath)).toBe(false);
  });
});
