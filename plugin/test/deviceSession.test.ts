import { describe, expect, it } from "vitest";
import { DeviceGateError, gateErrorFrom } from "../src/sync/deviceGate";
import { type DeviceReportResult, prepareConnection } from "../src/sync/deviceSession";
import type { PluginPolicy } from "../src/sync/pluginPolicy";

const OFF: PluginPolicy = { mode: "OFF", allowed: [] };
const ENFORCE: PluginPolicy = { mode: "ENFORCE", allowed: [] };

function result(over: Partial<DeviceReportResult>): DeviceReportResult {
  return { status: "COMPLIANT", compliant: true, offending: [], wipe: false, policy: OFF, ...over };
}

function setup(responses: DeviceReportResult[], disabled: string[] = []) {
  const log: string[] = [];
  const queue = [...responses];
  return {
    log,
    deps: {
      report: async (wiped: boolean) => { log.push(wiped ? "report(wiped)" : "report"); return queue.shift() ?? result({}); },
      rememberPolicy: (policy: PluginPolicy) => { log.push(`remember ${policy.mode}`); },
      enforce: async () => { log.push("enforce"); return disabled; },
      wipe: async () => { log.push("wipe"); },
    },
  };
}

describe("prepareConnection", () => {
  it("just reports and remembers the policy while the vault does not enforce", async () => {
    const { deps, log } = setup([result({})]);

    await prepareConnection(deps);

    expect(log).toEqual(["report", "remember OFF"]);
  });

  it("disables offending plugins and reports again before connecting", async () => {
    const { deps, log } = setup([
      result({ status: "NON_COMPLIANT", compliant: false, offending: ["kanban 1"], policy: ENFORCE }),
      result({ policy: ENFORCE }),
    ], ["kanban 1"]);

    await prepareConnection(deps);

    expect(log).toEqual(["report", "remember ENFORCE", "enforce", "report"]);
  });

  it("refuses with the plugin names when something non-compliant stays active", async () => {
    const bad = result({ status: "NON_COMPLIANT", compliant: false, offending: ["kanban 1"], policy: ENFORCE });
    const { deps } = setup([bad, bad], []);

    await expect(prepareConnection(deps)).rejects.toMatchObject({ code: "DEVICE_NOT_COMPLIANT", message: expect.stringContaining("kanban 1") });
  });

  it("wipes first, confirms to the server, then refuses", async () => {
    const { deps, log } = setup([result({ status: "WIPE_PENDING", compliant: false, wipe: true }), result({})]);

    await expect(prepareConnection(deps)).rejects.toMatchObject({ code: "DEVICE_WIPE" });

    expect(log).toEqual(["report", "remember OFF", "wipe", "report(wiped)"]);
  });

  it("refuses a revoked device without enforcing anything", async () => {
    const { deps, log } = setup([result({ status: "REVOKED", compliant: false, policy: ENFORCE })]);

    await expect(prepareConnection(deps)).rejects.toBeInstanceOf(DeviceGateError);
    expect(log).not.toContain("enforce");
  });
});

describe("gateErrorFrom", () => {
  const response = (status: number, body: unknown) => ({ status, json: async () => body }) as Pick<Response, "status" | "json">;

  it("recognises the server's refusal code and message", async () => {
    const error = await gateErrorFrom(response(403, { code: "DEVICE_REVOKED", detail: "Gesperrt." }));

    expect(error).toMatchObject({ code: "DEVICE_REVOKED", message: "Gesperrt." });
  });

  it("leaves other 403s and other statuses alone", async () => {
    expect(await gateErrorFrom(response(403, { detail: "no" }))).toBeNull();
    expect(await gateErrorFrom(response(500, { code: "DEVICE_REVOKED" }))).toBeNull();
    expect(await gateErrorFrom({ status: 403, json: async () => { throw new Error("x"); } })).toBeNull();
  });
});
