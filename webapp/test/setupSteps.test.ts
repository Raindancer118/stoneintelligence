import { beforeEach, describe, expect, it } from "vitest";
import { markConnectOpened, setupSteps, dismissSetup, setupDismissed } from "../src/lib/setupSteps";

describe("setup checklist", () => {
  beforeEach(() => localStorage.clear());

  it("lists what a manager still has to do, in order", () => {
    const steps = setupSteps({ vaultId: "v", hasNotes: false, members: 1, canManage: true });
    expect(steps.map(s => [s.id, s.done])).toEqual([["note", false], ["invite", false], ["obsidian", false]]);
  });

  it("leaves out inviting for people who cannot manage the vault", () => {
    expect(setupSteps({ vaultId: "v", hasNotes: true, members: null, canManage: false }).map(s => s.id)).toEqual(["note", "obsidian"]);
  });

  it("ticks steps off from what the vault already shows", () => {
    markConnectOpened("v");
    const steps = setupSteps({ vaultId: "v", hasNotes: true, members: 3, canManage: true });
    expect(steps.every(s => s.done)).toBe(true);
  });

  it("remembers the Obsidian step per vault", () => {
    markConnectOpened("a");
    expect(setupSteps({ vaultId: "b", hasNotes: true, members: null, canManage: false }).find(s => s.id === "obsidian")?.done).toBe(false);
  });

  it("stays hidden after someone dismissed it for this vault", () => {
    expect(setupDismissed("v")).toBe(false);
    dismissSetup("v");
    expect(setupDismissed("v")).toBe(true);
    expect(setupDismissed("w")).toBe(false);
  });
});
