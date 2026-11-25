import { describe, expect, it } from "vitest";
import { disabledNotice, isPermitted, parsePolicy, type PluginPolicy, refusalNotice, violations } from "../src/sync/pluginPolicy";

const enforce = (...allowed: { id: string; version?: string | null }[]): PluginPolicy => ({ mode: "ENFORCE", allowed });

describe("pluginPolicy", () => {
  it("allows the own plugin without an entry", () => {
    expect(isPermitted(enforce(), { id: "stoneintelligence", version: "0.34.0" })).toBe(true);
  });

  it("allows a listed plugin in any version, or only the pinned one", () => {
    const policy = enforce({ id: "dataview" }, { id: "calendar", version: "1.5.10" });

    expect(isPermitted(policy, { id: "dataview", version: "0.5.68" })).toBe(true);
    expect(isPermitted(policy, { id: "calendar", version: "1.5.10" })).toBe(true);
    expect(isPermitted(policy, { id: "calendar", version: "1.5.9" })).toBe(false);
    expect(isPermitted(policy, { id: "kanban", version: "2.0.51" })).toBe(false);
  });

  it("reports the offending plugins only while enforcing", () => {
    const enabled = [{ id: "calendar", version: "1.5.9" }, { id: "kanban", version: "1" }];

    expect(violations(enforce({ id: "calendar", version: "1.5.10" }), enabled).map((p) => p.id)).toEqual(["calendar", "kanban"]);
    expect(violations({ mode: "OFF", allowed: [] }, enabled)).toEqual([]);
    expect(violations(null, enabled)).toEqual([]);
  });

  it("parses server data defensively", () => {
    expect(parsePolicy({ mode: "ENFORCE", allowed: [{ id: "a", version: "" }, { id: 5 }, null, { id: "b", version: "2" }] }))
      .toEqual({ mode: "ENFORCE", allowed: [{ id: "a", version: null }, { id: "b", version: "2" }], alwaysAllowed: "stoneintelligence" });
    expect(parsePolicy({ mode: "MAYBE" })).toBeNull();
    expect(parsePolicy("x")).toBeNull();
  });

  it("words notices in German without exclamation marks", () => {
    expect(refusalNotice("Kanban")).toContain("„Kanban“");
    expect(refusalNotice("Kanban")).not.toContain("!");
    expect(disabledNotice(["kanban 1"])).toContain("deaktiviert");
    expect(disabledNotice(["a", "b"])).toContain("2 Plugins");
  });
});
