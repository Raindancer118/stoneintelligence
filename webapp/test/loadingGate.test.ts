import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { LoadingGate } from "../src/lib/loadingGate";

describe("loading gate", () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it("shows nothing for loads shorter than the delay, so fast answers do not flicker", () => {
    const seen: boolean[] = [];
    const gate = new LoadingGate(visible => seen.push(visible));
    gate.set(true);
    vi.advanceTimersByTime(250);
    gate.set(false);
    vi.advanceTimersByTime(1000);
    expect(seen).toEqual([]);
  });

  it("shows the skeleton after 300 ms and keeps it for at least 400 ms", () => {
    const seen: boolean[] = [];
    const gate = new LoadingGate(visible => seen.push(visible));
    gate.set(true);
    vi.advanceTimersByTime(300);
    expect(seen).toEqual([true]);
    vi.advanceTimersByTime(50);
    gate.set(false);
    vi.advanceTimersByTime(349);
    expect(seen).toEqual([true]);
    vi.advanceTimersByTime(1);
    expect(seen).toEqual([true, false]);
  });

  it("hides at once when the skeleton has been visible long enough", () => {
    const seen: boolean[] = [];
    const gate = new LoadingGate(visible => seen.push(visible));
    gate.set(true);
    vi.advanceTimersByTime(1000);
    gate.set(false);
    expect(seen).toEqual([true, false]);
  });

  it("stops all timers when disposed", () => {
    const seen: boolean[] = [];
    const gate = new LoadingGate(visible => seen.push(visible));
    gate.set(true);
    gate.dispose();
    vi.advanceTimersByTime(1000);
    expect(seen).toEqual([]);
  });
});
