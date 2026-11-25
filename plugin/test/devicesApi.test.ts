import { describe, expect, it, vi } from "vitest";
import { NoteApiClient } from "../src/sync/NoteApiClient";

function client(...responses: object[]) {
  const fakeFetch = vi.fn();
  for (const response of responses) {
    fakeFetch.mockResolvedValueOnce(response);
  }
  return { fakeFetch, api: new NoteApiClient("https://platform.example", vi.fn().mockResolvedValue("t"), fakeFetch as unknown as typeof fetch) };
}

const ok = (body: unknown) => ({ ok: true, json: async () => body, text: async () => (body === undefined ? "" : JSON.stringify(body)) });

describe("plugin policy and devices API", () => {
  it("reads and changes the policy", async () => {
    const { api, fakeFetch } = client(ok({ mode: "OFF", allowed: [] }), ok({ mode: "ENFORCE", allowed: [{ id: "calendar", version: null }] }));

    expect(await api.pluginPolicy("v1")).toEqual({ mode: "OFF", allowed: [] });
    await api.setPluginPolicy("v1", { mode: "ENFORCE", allowed: [{ id: "calendar", version: null }] });

    expect(fakeFetch).toHaveBeenLastCalledWith("https://platform.example/api/v1/vaults/v1/plugin-policy", expect.objectContaining({
      method: "PUT", body: JSON.stringify({ mode: "ENFORCE", allowed: [{ id: "calendar", version: null }] }),
    }));
  });

  it("reports this device under its own id", async () => {
    const { api, fakeFetch } = client(ok({ status: "COMPLIANT", compliant: true, offending: [], wipe: false, policy: { mode: "OFF", allowed: [] } }));

    const result = await api.reportDevice("v1", "dev-1234 5678", {
      name: "Laptop", platform: "linux", obsidianVersion: "1.15.0", pluginVersion: "0.34.0", restrictedMode: false, plugins: [], wiped: false,
    });

    expect(result.compliant).toBe(true);
    expect(fakeFetch).toHaveBeenCalledWith("https://platform.example/api/v1/vaults/v1/devices/dev-1234%205678", expect.objectContaining({ method: "PUT" }));
  });

  it("lists devices and sends revoke, wipe and reinstate", async () => {
    const { api, fakeFetch } = client(ok([]), ok(undefined), ok(undefined), ok(undefined));

    expect(await api.listDevices("v1")).toEqual([]);
    await api.revokeDevice("v1", "dev-1");
    await api.wipeDevice("v1", "dev-1");
    await api.reinstateDevice("v1", "dev-1");

    expect(fakeFetch.mock.calls.slice(1).map((call) => call[0])).toEqual([
      "https://platform.example/api/v1/vaults/v1/devices/dev-1/revoke",
      "https://platform.example/api/v1/vaults/v1/devices/dev-1/wipe",
      "https://platform.example/api/v1/vaults/v1/devices/dev-1/reinstate",
    ]);
  });
});
