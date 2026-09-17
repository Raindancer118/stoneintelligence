import { describe, expect, it } from "vitest";
import { connectionConfigJson } from "../src/lib/connectionConfig";

describe("connectionConfigJson", () => {
  it("should_produceParseableJson_withGivenVaultId", () => {
    const json = connectionConfigJson("vault-123");

    const parsed = JSON.parse(json);
    expect(parsed.vaultId).toBe("vault-123");
    expect(parsed).toHaveProperty("platformApiUrl");
    expect(parsed).toHaveProperty("platformWsUrl");
    expect(parsed).toHaveProperty("oidcIssuerUrl");
    expect(parsed).toHaveProperty("oidcClientId");
  });
});
