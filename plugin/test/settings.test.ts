import { describe, expect, it } from "vitest";
import {
  DEFAULT_SETTINGS, emptyVaultState, isExcluded, isExcludedFolder, migrateSettings, queueDelete, queueFolderOp, queueRename,
  wsUrlFor,
} from "../src/settings";

describe("migrateSettings", () => {
  it("should_startWithTheHostedDefaults_onAFreshInstall", () => {
    const settings = migrateSettings(null);

    expect(settings.platformApiUrl).toBe(DEFAULT_SETTINGS.platformApiUrl);
    expect(settings.oidcClientId).toBe(DEFAULT_SETTINGS.oidcClientId);
    expect(settings.vaults).toEqual({});
    expect(settings.pendingConnect).toBeNull();
    expect(settings.propertiesDefaultApplied).toBe(false);
  });

  // Ein frisch angelegter Obsidian-Vault bringt den Verbinden-Auftrag in seiner data.json mit.
  it("should_keepTheConnectRequest_ofANewlyCreatedVault", () => {
    const link = { vaultId: "2f719285-2483-4e59-89e0-334af2813a70", vaultName: "Team" };
    expect(migrateSettings({ pendingConnect: link }).pendingConnect).toEqual(link);
  });

  // Bestehende Installationen fuehren die Zuordnung Pfad -> NoteId noch global. Sie gehoert zu
  // genau einem Vault - sonst wuerde ein Vault-Wechsel fremde NoteIds weiterverwenden.
  it("should_moveTheLegacyNoteIdMap_underTheConfiguredVault", () => {
    const settings = migrateSettings({
      platformApiUrl: "https://example.org",
      vaultId: "vault-1",
      noteIds: { "a.md": "n1" },
      tokens: null,
    });

    expect(settings.vaults["vault-1"].noteIds).toEqual({ "a.md": "n1" });
    expect("noteIds" in settings).toBe(false);
    expect(settings.platformApiUrl).toBe("https://example.org");
  });

  it("should_replaceNeverUsedLocalhostDefaults_withTheHostedOnes", () => {
    const settings = migrateSettings({ platformApiUrl: "http://localhost:8080", platformWsUrl: "ws://localhost:8080", vaultId: "" });

    expect(settings.platformApiUrl).toBe(DEFAULT_SETTINGS.platformApiUrl);
    expect(settings.platformWsUrl).toBe("");
  });

  it("should_keepLocalhost_when_itIsActuallyInUse", () => {
    const settings = migrateSettings({ platformApiUrl: "http://localhost:8080", platformWsUrl: "ws://localhost:8080", vaultId: "v" });

    expect(settings.platformApiUrl).toBe("http://localhost:8080");
  });
});

describe("wsUrlFor", () => {
  it.each([
    ["https://sync.example.org", "", "wss://sync.example.org"],
    ["http://localhost:8080/", "", "ws://localhost:8080"],
    ["https://sync.example.org", "wss://other.example.org", "wss://other.example.org"],
  ])("api %s + override '%s' -> %s", (api, ws, expected) => {
    expect(wsUrlFor({ platformApiUrl: api, platformWsUrl: ws })).toBe(expected);
  });
});

describe("isExcluded", () => {
  it("should_matchWholeFolderPrefixes_only", () => {
    expect(isExcluded("Privat/Tagebuch.md", ["Privat"])).toBe(true);
    expect(isExcluded("Privat/Tief/x.md", ["Privat/"])).toBe(true);
    expect(isExcluded("Privatsache.md", ["Privat"])).toBe(false);
    expect(isExcluded("Arbeit/x.md", ["", "  "])).toBe(false);
  });
});

describe("pending operations", () => {
  it("should_keepOnlyTheLatestRename_perNote", () => {
    const state = emptyVaultState();
    queueRename(state, "n1", "b.md");
    queueRename(state, "n1", "c.md");

    expect(state.pendingOps).toEqual([{ kind: "rename", noteId: "n1", path: "c.md" }]);
  });

  it("should_dropPendingRenames_when_theNoteIsDeletedAnyway", () => {
    const state = emptyVaultState();
    queueRename(state, "n1", "b.md");
    queueDelete(state, "n1", "b.md", "op-1");

    expect(state.pendingOps).toEqual([{ kind: "delete", noteId: "n1", path: "b.md", operationId: "op-1" }]);
  });
});

describe("Ordner", () => {
  // Nach dem Update gab es noch keinen Ordnerabgleich - das muss der Plan erkennen koennen.
  it("should_startWithoutKnownFolders_forExistingInstallations", () => {
    const settings = migrateSettings({ vaultId: "v1", vaults: { v1: { noteIds: {}, noteMeta: {}, pendingOps: [], blockedPaths: {} } } });

    expect(settings.vaults.v1.knownFolders).toBeNull();
  });

  // Frisch verbunden: alles hier ist neu - auch leere Ordner werden hochgeladen, nicht weggeraeumt.
  it("should_knowNoFoldersYet_forAFreshlyConnectedVault", () => {
    expect(emptyVaultState().knownFolders).toEqual([]);
  });

  it("should_queueFolderOperations_inOrder_andForgetDeletedFolders", () => {
    const state = { ...emptyVaultState(), knownFolders: ["A", "A/B", "AB"] };

    queueFolderOp(state, { kind: "folderCreate", path: "Neu" });
    queueFolderOp(state, { kind: "folderRename", path: "Neu", to: "Alt/Neu" });
    queueFolderOp(state, { kind: "folderDelete", path: "A" });

    expect(state.pendingOps).toEqual([
      { kind: "folderCreate", path: "Neu" },
      { kind: "folderRename", path: "Neu", to: "Alt/Neu" },
      { kind: "folderDelete", path: "A" },
    ]);
    expect(state.knownFolders).toEqual(["AB"]);
  });

  it("should_moveKnownFoldersAlong_whenRenaming", () => {
    const state = { ...emptyVaultState(), knownFolders: ["A", "A/B", "C"] };

    queueFolderOp(state, { kind: "folderRename", path: "A", to: "Z/A" });

    expect(state.knownFolders).toEqual(["C", "Z/A", "Z/A/B"]);
  });

  it("should_treatAnExcludedFolderItself_asExcluded", () => {
    expect(isExcludedFolder("Privat", ["Privat"])).toBe(true);
    expect(isExcludedFolder("Privat/Tief", ["Privat"])).toBe(true);
    expect(isExcludedFolder("Privatsache", ["Privat"])).toBe(false);
  });
});

describe("Dateien", () => {
  // Installationen vor der Datei-Synchronisation (ADR 0009) starten ohne verknuepfte Dateien.
  it("should_startWithoutFileMappings_forExistingInstallations", () => {
    const settings = migrateSettings({ vaultId: "v1", vaults: { v1: { noteIds: { "a.md": "n1" }, noteMeta: {}, pendingOps: [], blockedPaths: {} } } });

    expect(settings.vaults.v1.fileIds).toEqual({});
    expect(settings.vaults.v1.fileMeta).toEqual({});
    expect(settings.vaults.v1.noteIds).toEqual({ "a.md": "n1" });
  });

  it("should_dropAQueuedRename_ofAFileThatIsDeletedAfterwards", () => {
    const state = emptyVaultState();
    queueRename(state, "f1", "Archiv/a.pdf");
    queueDelete(state, "f1", "Archiv/a.pdf", "op-1");

    expect(state.pendingOps).toEqual([{ kind: "delete", noteId: "f1", path: "Archiv/a.pdf", operationId: "op-1" }]);
  });
});

