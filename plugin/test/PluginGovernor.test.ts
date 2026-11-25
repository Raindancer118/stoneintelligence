import { describe, expect, it } from "vitest";
import { type ObsidianPlugins, PluginGovernor } from "../src/sync/PluginGovernor";
import type { PluginPolicy } from "../src/sync/pluginPolicy";

const enforce = (...ids: string[]): PluginPolicy => ({ mode: "ENFORCE", allowed: ids.map((id) => ({ id })) });

function fakeObsidian(active: Record<string, string>) {
  const calls: string[] = [];
  const plugins: ObsidianPlugins & { loaded: Record<string, unknown> } = {
    loaded: Object.fromEntries(Object.keys(active).map((id) => [id, {}])),
    get plugins() { return this.loaded; },
    manifests: Object.fromEntries(Object.entries(active).map(([id, version]) => [id, { id, name: id.toUpperCase(), version }])),
    isEnabled: () => true,
    disablePluginAndSave: async function (this: { loaded: Record<string, unknown> }, id: string) { calls.push(`disable ${id}`); delete this.loaded[id]; },
    enablePluginAndSave: async (id: string) => { calls.push(`enable ${id}`); return true; },
    installPlugin: async (repo: string) => { calls.push(`install ${repo}`); return true; },
  };
  return { plugins, calls };
}

function governor(plugins: ObsidianPlugins | undefined) {
  const notices: string[] = [];
  return { notices, governor: new PluginGovernor(() => plugins, (message) => notices.push(message), () => undefined) };
}

describe("PluginGovernor", () => {
  it("lists the active community plugins with versions", () => {
    const { plugins } = fakeObsidian({ calendar: "1.5.10", kanban: "2.0.51" });

    expect(governor(plugins).governor.enabled()).toEqual([{ id: "calendar", version: "1.5.10" }, { id: "kanban", version: "2.0.51" }]);
  });

  it("disables only the plugins the policy does not allow, and says so", async () => {
    const { plugins, calls } = fakeObsidian({ stoneintelligence: "0.34.0", calendar: "1.5.10", kanban: "2.0.51" });
    const { governor: g, notices } = governor(plugins);
    g.setPolicy(enforce("calendar"));

    const disabled = await g.enforce();

    expect(disabled).toEqual(["kanban 2.0.51"]);
    expect(calls).toEqual(["disable kanban"]);
    expect(notices).toHaveLength(1);
    expect(notices[0]).toContain("kanban 2.0.51");
  });

  it("does nothing while the policy is off", async () => {
    const { plugins, calls } = fakeObsidian({ kanban: "1" });
    const { governor: g } = governor(plugins);
    g.setPolicy({ mode: "OFF", allowed: [] });

    expect(await g.enforce()).toEqual([]);
    expect(calls).toEqual([]);
    expect(g.guardState()).toBe("inactive");
  });

  it("refuses installing a plugin that is not allowed, and lets allowed ones through", async () => {
    const { plugins, calls } = fakeObsidian({});
    const { governor: g, notices } = governor(plugins);
    g.setPolicy({ mode: "ENFORCE", allowed: [{ id: "calendar", version: "1.5.10" }] });

    expect(await plugins.installPlugin!("mgmeyers/obsidian-kanban", "2.0.51", { id: "obsidian-kanban", name: "Kanban" })).toBe(false);
    expect(await plugins.installPlugin!("liamcain/obsidian-calendar-plugin", "1.5.9", { id: "calendar", name: "Calendar" })).toBe(false);
    expect(await plugins.installPlugin!("liamcain/obsidian-calendar-plugin", "1.5.10", { id: "calendar", name: "Calendar" })).toBe(true);

    expect(calls).toEqual(["install liamcain/obsidian-calendar-plugin"]);
    expect(notices[0]).toContain("„Kanban“");
    expect(g.guardState()).toBe("active");
  });

  it("refuses enabling an installed plugin that is not allowed", async () => {
    const { plugins, calls } = fakeObsidian({});
    plugins.manifests = { kanban: { id: "kanban", name: "Kanban", version: "1" } };
    const { governor: g } = governor(plugins);
    g.setPolicy(enforce());

    expect(await plugins.enablePluginAndSave!("kanban")).toBe(false);
    expect(calls).toEqual([]);
  });

  it("removes its guards again when the policy is lifted", async () => {
    const { plugins, calls } = fakeObsidian({});
    const original = plugins.installPlugin;
    const { governor: g } = governor(plugins);
    g.setPolicy(enforce());
    expect(plugins.installPlugin).not.toBe(original);

    g.setPolicy({ mode: "OFF", allowed: [] });

    expect(plugins.installPlugin).toBe(original);
    await plugins.installPlugin!("any/repo", "1", { id: "x" });
    expect(calls).toEqual(["install any/repo"]);
  });

  it("copes with an Obsidian that lacks the internals", async () => {
    const { governor: g } = governor({});
    g.setPolicy(enforce());

    expect(g.guardState()).toBe("unavailable");
    expect(g.enabled()).toEqual([]);
    expect(await g.enforce()).toEqual([]);
    expect(governor(undefined).governor.restrictedMode()).toBe(false);
  });

  it("keeps going when one plugin refuses to be disabled", async () => {
    const { plugins } = fakeObsidian({ a: "1", b: "1" });
    let first = true;
    plugins.disablePluginAndSave = async (id: string) => {
      if (first) { first = false; throw new Error(`boom ${id}`); }
    };
    const { governor: g } = governor(plugins);
    g.setPolicy(enforce());

    expect(await g.enforce()).toEqual(["b 1"]);
  });

  it("reports restricted mode", () => {
    const { plugins } = fakeObsidian({});
    plugins.isEnabled = () => false;

    expect(governor(plugins).governor.restrictedMode()).toBe(true);
  });
});
