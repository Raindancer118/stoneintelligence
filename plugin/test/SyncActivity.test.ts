import { describe, expect, it, vi } from "vitest";
import { formatRelativeTime, SyncActivity } from "../src/sync/SyncActivity";

function ready(): SyncActivity {
  const activity = new SyncActivity(() => 1_000_000);
  activity.update({ configured: true, signedIn: true, connection: "online", paused: false });
  return activity;
}

describe("SyncActivity", () => {
  describe("phase", () => {
    it("should_askForSetup_before_anythingElse", () => {
      const activity = new SyncActivity();
      activity.update({ configured: false, signedIn: false });

      expect(activity.phase()).toBe("notConfigured");
    });

    it("should_reportSignedOut_when_configuredWithoutLogin", () => {
      const activity = new SyncActivity();
      activity.update({ configured: true, signedIn: false });

      expect(activity.phase()).toBe("signedOut");
    });

    it("should_reportPaused_overConnectionState", () => {
      const activity = ready();
      activity.update({ paused: true, connection: "offline" });

      expect(activity.phase()).toBe("paused");
    });

    it("should_reportSyncing_whileAPassRuns", () => {
      const activity = ready();
      activity.beginPass(10);
      activity.advancePass();

      expect(activity.phase()).toBe("syncing");
      expect(activity.progress()).toEqual({ done: 1, total: 10 });
    });

    it("should_reportOffline_when_theConnectionIsDown", () => {
      const activity = ready();
      activity.update({ connection: "offline" });

      expect(activity.phase()).toBe("offline");
    });

    it("should_reportAttention_when_problemsRemain_afterAPass", () => {
      const activity = ready();
      activity.reportProblem("a.md", "Keine Berechtigung");

      expect(activity.phase()).toBe("attention");
      expect(activity.problems()).toEqual([{ path: "a.md", message: "Keine Berechtigung", at: 1_000_000 }]);
    });

    it("should_reportSynced_after_aCleanPass", () => {
      const activity = ready();
      activity.beginPass(1);
      activity.endPass();

      expect(activity.phase()).toBe("synced");
      expect(activity.lastSyncedAt()).toBe(1_000_000);
    });
  });

  it("should_carryDecisionActions_withAProblem", () => {
    const activity = ready();
    const keep = vi.fn();
    activity.reportProblem("a.md", "Anderswo gelöscht", [{ label: "Behalten", run: keep }]);

    const [problem] = activity.problems();
    problem.actions?.[0].run();

    expect(problem.actions?.map((action) => action.label)).toEqual(["Behalten"]);
    expect(keep).toHaveBeenCalled();
  });

  it("should_clearAProblem_onceThatPathSyncsAgain", () => {
    const activity = ready();
    activity.reportProblem("a.md", "Fehler");

    activity.clearProblem("a.md");

    expect(activity.problems()).toEqual([]);
  });

  it("should_keepOnlyTheMostRecentLogEntries_newestFirst", () => {
    const activity = ready();
    for (let i = 0; i < 60; i++) {
      activity.log("pulled", `n${i}.md`);
    }

    const entries = activity.recent();
    expect(entries).toHaveLength(50);
    expect(entries[0].path).toBe("n59.md");
  });

  it("should_notifySubscribers_andStopAfterUnsubscribe", () => {
    const activity = ready();
    const listener = vi.fn();
    const unsubscribe = activity.subscribe(listener);

    activity.log("pushed", "a.md");
    unsubscribe();
    activity.log("pushed", "b.md");

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("should_notifyOnTouch_forChangesOutsideTheModel_likePresence", () => {
    const activity = ready();
    const listener = vi.fn();
    activity.subscribe(listener);

    activity.touch();

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("should_notNotify_when_anUpdateChangesNothing", () => {
    const activity = ready();
    const listener = vi.fn();
    activity.subscribe(listener);

    activity.update({ connection: "online" });

    expect(listener).not.toHaveBeenCalled();
  });
});

describe("formatRelativeTime", () => {
  const now = 10_000_000;

  it.each([
    [now - 5_000, "gerade eben"],
    [now - 3 * 60_000, "vor 3 Min."],
    [now - 2 * 3_600_000, "vor 2 Std."],
    [now - 3 * 86_400_000, "vor 3 Tagen"],
  ])("formats %i", (at, expected) => {
    expect(formatRelativeTime(at, now)).toBe(expected);
  });
});
