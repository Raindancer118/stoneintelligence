import { afterEach, describe, expect, it, vi } from "vitest";
import { withTransition } from "../src/lib/motion";

function motion(reduced: boolean) {
  vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: reduced })));
}
afterEach(() => { vi.unstubAllGlobals(); delete (document as { startViewTransition?: unknown }).startViewTransition; });

describe("view transitions", () => {
  it("just applies the change where the browser has no view transitions", async () => {
    motion(false);
    const update = vi.fn();
    await withTransition(update);
    expect(update).toHaveBeenCalledOnce();
  });
  it("animates the change when the browser can and motion is welcome", async () => {
    motion(false);
    const start = vi.fn((callback: () => Promise<void>) => { void callback(); return { finished: Promise.resolve() }; });
    Object.assign(document, { startViewTransition: start });
    const update = vi.fn();
    await withTransition(update);
    expect(start).toHaveBeenCalledOnce();
    expect(update).toHaveBeenCalledOnce();
  });
  it("names the kind of change, so area and note switches can move differently", async () => {
    motion(false);
    const start = vi.fn((options: { update: () => Promise<void>; types: string[] }) => { void options.update(); return { finished: Promise.resolve() }; });
    Object.assign(document, { startViewTransition: start });
    const update = vi.fn();
    await withTransition(update, "note");
    expect(start.mock.calls[0][0].types).toEqual(["note"]);
    expect(update).toHaveBeenCalledOnce();
  });
  it("skips the animation when the user prefers reduced motion", async () => {
    motion(true);
    const start = vi.fn();
    Object.assign(document, { startViewTransition: start });
    const update = vi.fn();
    await withTransition(update);
    expect(start).not.toHaveBeenCalled();
    expect(update).toHaveBeenCalledOnce();
  });
});

