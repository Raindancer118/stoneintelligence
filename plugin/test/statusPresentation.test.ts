import { describe, expect, it } from "vitest";
import { SyncActivity } from "../src/sync/SyncActivity";
import { presentStatus } from "../src/ui/statusPresentation";

function activity(setup: (a: SyncActivity) => void): SyncActivity {
  const a = new SyncActivity(() => 1_000_000);
  a.update({ configured: true, signedIn: true, connection: "online" });
  setup(a);
  return a;
}

describe("presentStatus", () => {
  it("should_inviteToSetUp_before_configuration", () => {
    const status = presentStatus(activity((a) => a.update({ configured: false })), { pending: 0 });

    expect(status.label).toBe("Sync einrichten");
    expect(status.action).toBe("settings");
  });

  it("should_offerSignIn_when_signedOut", () => {
    const status = presentStatus(activity((a) => a.update({ signedIn: false })), { pending: 0 });

    expect(status.label).toBe("Nicht angemeldet");
    expect(status.action).toBe("login");
  });

  it("should_showProgress_whileSyncing", () => {
    const status = presentStatus(activity((a) => {
      a.beginPass(10);
      a.advancePass();
      a.advancePass();
    }), { pending: 0 });

    expect(status.label).toBe("Synchronisiere 2/10");
    expect(status.icon).toBe("refresh-cw");
    expect(status.spinning).toBe(true);
  });

  it("should_mentionWaitingChanges_whenOffline", () => {
    const status = presentStatus(activity((a) => a.update({ connection: "offline" })), { pending: 3 });

    expect(status.label).toBe("Offline · 3 ausstehend");
    expect(status.tone).toBe("muted");
  });

  it("should_countProblems", () => {
    const status = presentStatus(activity((a) => {
      a.reportProblem("a.md", "x");
      a.reportProblem("b.md", "y");
    }), { pending: 0 });

    expect(status.label).toBe("2 Probleme");
    expect(status.tone).toBe("warning");
  });

  it("should_sayWhenItLastSynced_inTheTooltip", () => {
    const status = presentStatus(activity((a) => {
      a.beginPass(0);
      a.endPass();
    }), { pending: 0 }, 1_000_000 + 120_000);

    expect(status.label).toBe("Synchron");
    expect(status.tooltip).toContain("vor 2 Min.");
  });
});
