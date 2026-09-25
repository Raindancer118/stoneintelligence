import { describe, expect, it } from "vitest";
import { findLinkedVault, newVaultFiles, suggestVaultPath, vaultFolderName, vaultPathForPickedFolder } from "../src/sync/localVaultPlan";

const STONE_VAULT = "2f719285-2483-4e59-89e0-334af2813a70";

describe("findLinkedVault", () => {
  const vaults = [
    { id: "a", path: "/home/u/Privat", open: true },
    { id: "b", path: "/home/u/Team", open: false },
    { id: "c", path: "/home/u/Kaputt", open: false },
  ];
  const data: Record<string, unknown> = {
    "/home/u/Privat": { vaultId: "" },
    "/home/u/Team": { vaultId: STONE_VAULT, vaultName: "Team" },
    "/home/u/Kaputt": "kein json-objekt",
  };

  it("should_findTheLocalVaultAlreadyConnectedToTheSharedVault", () => {
    expect(findLinkedVault(vaults, "/home/u/Privat", STONE_VAULT, (path) => data[path] ?? null)).toEqual(vaults[1]);
  });

  it("should_returnNull_whenNoLocalVaultIsConnectedYet", () => {
    expect(findLinkedVault(vaults, "/home/u/Privat", "00000000-0000-0000-0000-000000000000", (path) => data[path] ?? null)).toBeNull();
  });

  // Der gerade offene Vault wird vom Aufrufer selbst behandelt ("schon verbunden").
  it("should_ignoreTheCurrentVault", () => {
    expect(findLinkedVault(vaults, "/home/u/Team", STONE_VAULT, (path) => data[path] ?? null)).toBeNull();
  });

  it("should_skipVaultsWhosePluginDataCannotBeRead", () => {
    expect(findLinkedVault(vaults, "/home/u/Privat", STONE_VAULT, (path) => {
      if (path === "/home/u/Team") {
        throw new Error("EACCES");
      }
      return data[path] ?? null;
    })).toBeNull();
  });
});

describe("vaultFolderName", () => {
  it("should_keepAReadableName", () => {
    expect(vaultFolderName("Team-Notizen 2026")).toBe("Team-Notizen 2026");
  });

  it("should_removeCharactersNoFileSystemAccepts", () => {
    expect(vaultFolderName('A/B\\C:D*E?"F<G>H|I')).toBe("A B C D E F G H I");
  });

  it("should_notAllowPathTraversalOrHiddenFolders", () => {
    expect(vaultFolderName("../..")).toBe("StoneIntelligence");
    expect(vaultFolderName(".obsidian")).toBe("obsidian");
  });

  it("should_fallBackToADefault_forEmptyNames", () => {
    expect(vaultFolderName("   ")).toBe("StoneIntelligence");
    expect(vaultFolderName(null)).toBe("StoneIntelligence");
  });
});

describe("suggestVaultPath", () => {
  it("should_placeTheNewVaultNextToTheCurrentOne", () => {
    expect(suggestVaultPath("/home/u/Dokumente/Privat", "Team", () => false)).toBe("/home/u/Dokumente/Team");
  });

  it("should_notReuseAnExistingFolder", () => {
    const taken = new Set(["/home/u/Team", "/home/u/Team 2"]);
    expect(suggestVaultPath("/home/u/Privat", "Team", (path) => taken.has(path))).toBe("/home/u/Team 3");
  });

  it("should_handleWindowsPaths", () => {
    expect(suggestVaultPath("C:\\Users\\u\\Privat", "Team", () => false)).toBe("C:\\Users\\u\\Team");
  });
});

describe("vaultPathForPickedFolder", () => {
  // Wer im Ordner-Dialog einen leeren Ordner anlegt oder waehlt, meint genau diesen als Vault.
  it("should_useAnEmptyPickedFolderAsTheVault", () => {
    expect(vaultPathForPickedFolder("/home/u/Team", true, "Team Notizen", () => true)).toBe("/home/u/Team");
  });

  it("should_createTheVaultInside_aPickedFolderWithContent", () => {
    expect(vaultPathForPickedFolder("/home/u/Dokumente", false, "Team Notizen", () => false)).toBe("/home/u/Dokumente/Team Notizen");
  });

  it("should_notReuseAnExistingFolder_insideThePickedOne", () => {
    const taken = new Set(["/home/u/Dokumente/Team"]);
    expect(vaultPathForPickedFolder("/home/u/Dokumente/", false, "Team", (path) => taken.has(path))).toBe("/home/u/Dokumente/Team 2");
  });

  it("should_handleWindowsPaths", () => {
    expect(vaultPathForPickedFolder("D:\\Vaults", false, "Team", () => false)).toBe("D:\\Vaults\\Team");
  });
});

describe("newVaultFiles", () => {
  const files = newVaultFiles({
    pluginId: "stoneintelligence",
    pluginFiles: { "main.js": "code", "manifest.json": "{}", "styles.css": "css" },
    settings: {
      platformApiUrl: "https://api.example",
      platformWsUrl: "",
      oidcIssuerUrl: "https://sso.example/",
      oidcClientId: "client",
    },
    link: { vaultId: STONE_VAULT, vaultName: "Team" },
  });
  const byPath = Object.fromEntries(files.map((file) => [file.path, file.content]));

  it("should_installAndEnableThePlugin", () => {
    expect(byPath[".obsidian/plugins/stoneintelligence/main.js"]).toBe("code");
    expect(byPath[".obsidian/plugins/stoneintelligence/manifest.json"]).toBe("{}");
    expect(byPath[".obsidian/plugins/stoneintelligence/styles.css"]).toBe("css");
    expect(JSON.parse(byPath[".obsidian/community-plugins.json"])).toEqual(["stoneintelligence"]);
  });

  it("should_carryOverTheServerSettingsAndTheVaultToConnect", () => {
    expect(JSON.parse(byPath[".obsidian/plugins/stoneintelligence/data.json"])).toEqual({
      platformApiUrl: "https://api.example",
      platformWsUrl: "",
      oidcIssuerUrl: "https://sso.example/",
      oidcClientId: "client",
      pendingConnect: { vaultId: STONE_VAULT, vaultName: "Team" },
    });
  });

  // Tokens werden nie kopiert: Authentik rotiert Refresh-Tokens, zwei Vaults mit demselben Token
  // wuerden sich gegenseitig abmelden. Der neue Vault meldet sich selbst an (SSO, ein Klick).
  it("should_neverCopyTokens", () => {
    expect(byPath[".obsidian/plugins/stoneintelligence/data.json"]).not.toContain("token");
  });
});
