import { describe, expect, it } from "vitest";
import { CONNECT_ACTION, parseConnectLink } from "../src/sync/connectLink";

describe("parseConnectLink", () => {
  it("should_readVaultIdAndName_fromTheObsidianLink", () => {
    expect(parseConnectLink({ action: CONNECT_ACTION, stoneVault: "2F719285-2483-4E59-89E0-334AF2813A70", name: "Team-Notizen" }))
      .toEqual({ vaultId: "2f719285-2483-4e59-89e0-334af2813a70", vaultName: "Team-Notizen" });
  });

  it("should_rejectAnythingThatIsNotAVaultId", () => {
    for (const vault of ["", "abc", "../../etc", "2f719285-2483-4e59-89e0-334af2813a70x"]) {
      expect(parseConnectLink({ action: CONNECT_ACTION, vault }), vault).toBeNull();
    }
  });

  // Ein praeparierter Link darf das Plugin nie auf einen anderen Server umleiten - er waehlt nur
  // den Vault auf dem bereits eingestellten Server.
  it("should_ignoreServerOrLoginParameters", () => {
    const parsed = parseConnectLink({
      action: CONNECT_ACTION, stoneVault: "2f719285-2483-4e59-89e0-334af2813a70", platformApiUrl: "https://evil.example", oidcIssuerUrl: "x",
    });

    expect(parsed).toEqual({ vaultId: "2f719285-2483-4e59-89e0-334af2813a70", vaultName: null });
  });

  // Obsidian selbst liest `vault` als Namen des zu oeffnenden Obsidian-Vaults - ein Link mit
  // `vault=<uuid>` wird von Obsidian verworfen, bevor das Plugin ihn sieht (live beobachtet).
  it("should_notUseObsidiansOwnVaultParameter", () => {
    expect(parseConnectLink({ action: CONNECT_ACTION, vault: "2f719285-2483-4e59-89e0-334af2813a70" })).toBeNull();
  });

  it("should_capOverlongNames", () => {
    const parsed = parseConnectLink({ action: CONNECT_ACTION, stoneVault: "2f719285-2483-4e59-89e0-334af2813a70", name: "x".repeat(500) });

    expect(parsed?.vaultName).toHaveLength(120);
  });
});
