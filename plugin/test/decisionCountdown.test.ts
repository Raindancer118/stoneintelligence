import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DecisionCountdown } from "../src/ui/decisionCountdown";

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe("DecisionCountdown", () => {
  it("should_countDownEverySecond_andRunTheDefaultWhenTimeIsUp", () => {
    const ticks: number[] = [];
    const onDefault = vi.fn();
    const countdown = new DecisionCountdown(15, (left) => ticks.push(left), onDefault);

    countdown.start();
    vi.advanceTimersByTime(14_000);
    expect(onDefault).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1_000);

    expect(onDefault).toHaveBeenCalledTimes(1);
    expect(ticks[0]).toBe(15);
    expect(ticks.at(-1)).toBe(0);
  });

  it("should_neverRunTheDefault_afterTheOtherChoiceWasMade", () => {
    const onDefault = vi.fn();
    const countdown = new DecisionCountdown(15, () => undefined, onDefault);
    countdown.start();

    countdown.cancel();
    vi.advanceTimersByTime(60_000);

    expect(onDefault).not.toHaveBeenCalled();
  });

  it("should_runTheDefaultOnlyOnce_evenIfTriggeredEarly", () => {
    const onDefault = vi.fn();
    const countdown = new DecisionCountdown(15, () => undefined, onDefault);
    countdown.start();

    countdown.finishNow();
    vi.advanceTimersByTime(60_000);
    countdown.finishNow();

    expect(onDefault).toHaveBeenCalledTimes(1);
  });
});
