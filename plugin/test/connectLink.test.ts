import { describe, expect, it } from "vitest";
import { CONNECT_ACTION, parseConnectLink } from "../src/sync/connectLink";

describe("parseConnectLink", () => {
  it("should_readVaultIdAndName_fromTheObsidianLink", () => {
    expect(parseConnectLink({ action: CONNECT_ACTION, stoneVault: "2F719285-2483-4E59-89E0-334AF2813A70", name: "Team-Notizen" }))
      .toEqual({ vaultId: "2f719285-2483-4e59-89e0-334af2813a70", vaultName: "Team-Notizen", server: null });
  });

  it("should_rejectAnythingThatIsNotAVaultId", () => {
    for (const vault of ["", "abc", "../../etc", "2f719285-2483-4e59-89e0-334af2813a70x"]) {
      expect(parseConnectLink({ action: CONNECT_ACTION, vault }), vault).toBeNull();
    }
  });

  it("should_carryNoServer_whenTheLinkNamesNone", () => {
    expect(parseConnectLink({ action: CONNECT_ACTION, stoneVault: "2f719285-2483-4e59-89e0-334af2813a70" })?.server).toBeNull();
  });

  // Selbst gehostete Instanzen: die Einrichtungsseite nennt ihren eigenen Server. Uebernommen wird
  // er erst nach Bestaetigung im Verbinden-Dialog (s. serverSwitchWarning).
  it("should_readTheServerOfASelfHostedInstance", () => {
    const parsed = parseConnectLink({
      action: CONNECT_ACTION, stoneVault: "2f719285-2483-4e59-89e0-334af2813a70",
      server: "https://notes.example.org", ws: "wss://notes.example.org", issuer: "https://sso.example.org/application/o/si/", client: "abc123",
    });

    expect(parsed?.server).toEqual({
      platformApiUrl: "https://notes.example.org", platformWsUrl: "wss://notes.example.org",
      oidcIssuerUrl: "https://sso.example.org/application/o/si/", oidcClientId: "abc123",
    });
  });

  it("should_deriveTheWebSocket_whenTheLinkOmitsIt", () => {
    const parsed = parseConnectLink({
      action: CONNECT_ACTION, stoneVault: "2f719285-2483-4e59-89e0-334af2813a70",
      server: "https://notes.example.org", issuer: "https://sso.example.org/", client: "abc123",
    });

    expect(parsed?.server?.platformWsUrl).toBe("");
  });

  it("should_allowPlainHttpOnlyOnThisMachine", () => {
    const link = (server: string) => parseConnectLink({
      action: CONNECT_ACTION, stoneVault: "2f719285-2483-4e59-89e0-334af2813a70", server, issuer: "http://localhost:9000/", client: "dev",
    });

    expect(link("http://localhost:8080")?.server?.platformApiUrl).toBe("http://localhost:8080");
    expect(link("http://127.0.0.1:8080")?.server?.platformApiUrl).toBe("http://127.0.0.1:8080");
    expect(link("http://notes.example.org")).toBeNull();
  });

  // Lieber gar nicht verbinden als mit halben oder kaputten Serverdaten auf dem falschen Server.
  it("should_rejectIncompleteOrInvalidServerData", () => {
    const base = { action: CONNECT_ACTION, stoneVault: "2f719285-2483-4e59-89e0-334af2813a70" };
    const cases: Array<Record<string, string>> = [
      { server: "https://notes.example.org" },
      { server: "https://notes.example.org", issuer: "https://sso.example.org/" },
      { server: "javascript:alert(1)", issuer: "https://sso.example.org/", client: "x" },
      { server: "https://notes.example.org", issuer: "ftp://sso.example.org/", client: "x" },
      { server: "https://notes.example.org", issuer: "https://sso.example.org/", client: "x", ws: "https://notes.example.org" },
      { server: "https://notes.example.org", issuer: "https://sso.example.org/", client: "mit leerzeichen" },
      { server: "https://user:pw@notes.example.org", issuer: "https://sso.example.org/", client: "x" },
    ];
    for (const extra of cases) {
      expect(parseConnectLink({ ...base, ...extra }), JSON.stringify(extra)).toBeNull();
    }
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
