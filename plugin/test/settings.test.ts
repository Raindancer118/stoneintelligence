import { describe, expect, it } from "vitest";
import {
  DEFAULT_SETTINGS, emptyVaultState, isExcluded, migrateSettings, queueDelete, queueRename, wsUrlFor,
} from "../src/settings";

describe("migrateSettings", () => {
  it("should_startWithTheHostedDefaults_onAFreshInstall", () => {
    const settings = migrateSettings(null);

    expect(settings.platformApiUrl).toBe(DEFAULT_SETTINGS.platformApiUrl);
    expect(settings.oidcClientId).toBe(DEFAULT_SETTINGS.oidcClientId);
    expect(settings.vaults).toEqual({});
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
