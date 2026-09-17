import { describe, expect, it, vi } from "vitest";
import { dedupeInFlight } from "../src/sync/dedupeInFlight";

describe("dedupeInFlight", () => {
  it("should_callTheFunctionOnlyOnce_when_multipleCallsHappenBeforeTheFirstResolves", async () => {
    let resolveFirst: (value: string) => void = () => {};
    const fn = vi.fn().mockReturnValue(new Promise<string>((resolve) => { resolveFirst = resolve; }));
    const dedupe = dedupeInFlight<string>();

    const callA = dedupe(fn);
    const callB = dedupe(fn);
    resolveFirst("result");

    expect(await callA).toBe("result");
    expect(await callB).toBe("result");
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it("should_allowAFreshCall_afterThePreviousOneResolved", async () => {
    const fn = vi.fn().mockResolvedValueOnce("first").mockResolvedValueOnce("second");
    const dedupe = dedupeInFlight<string>();

    const first = await dedupe(fn);
    const second = await dedupe(fn);

    expect(first).toBe("first");
    expect(second).toBe("second");
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it("should_allowAFreshCall_afterThePreviousOneRejected", async () => {
    // Kritisch fuer den eigentlichen Bugfix: ein fehlgeschlagener Refresh (z. B. HTTP 400, weil
    // ein anderer gleichzeitiger Aufruf den Refresh-Token bereits verbraucht hat) darf NICHT
    // dauerhaft blockieren - der naechste Zugriffsversuch muss einen frischen Versuch ausloesen
    // koennen, statt fuer immer an derselben fehlgeschlagenen Promise haengen zu bleiben.
    const fn = vi.fn().mockRejectedValueOnce(new Error("HTTP 400")).mockResolvedValueOnce("recovered");
    const dedupe = dedupeInFlight<string>();

    await expect(dedupe(fn)).rejects.toThrow("HTTP 400");
    const second = await dedupe(fn);

    expect(second).toBe("recovered");
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it("should_propagateTheSameRejection_toAllConcurrentCallers", async () => {
    let rejectFirst: (error: Error) => void = () => {};
    const fn = vi.fn().mockReturnValue(new Promise<string>((_, reject) => { rejectFirst = reject; }));
    const dedupe = dedupeInFlight<string>();

    const callA = dedupe(fn);
    const callB = dedupe(fn);
    rejectFirst(new Error("boom"));

    await expect(callA).rejects.toThrow("boom");
    await expect(callB).rejects.toThrow("boom");
    expect(fn).toHaveBeenCalledTimes(1);
  });
});
