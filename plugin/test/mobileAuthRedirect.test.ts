import { describe, expect, it, vi } from "vitest";
import { awaitMobileRedirect, handleMobileRedirectCallback, type PendingAuthCallback } from "../src/sync/mobileAuthRedirect";

describe("awaitMobileRedirect", () => {
  it("should_timeOutAndClearThePendingLogin", async () => {
    const holder = { pending: null as PendingAuthCallback | null };
    await expect(awaitMobileRedirect("s", holder, 20)).rejects.toThrow(/timed out/);
    expect(holder.pending).toBeNull();
  });

  it("should_rejectTheEarlierLogin_whenANewOneStarts", async () => {
    const holder = { pending: null as PendingAuthCallback | null };
    const first = awaitMobileRedirect("a", holder).catch((e: Error) => e.message);
    const second = awaitMobileRedirect("b", holder);
    expect(await first).toMatch(/cancelled/);
    handleMobileRedirectCallback(holder.pending, { state: "b", code: "c" });
    expect(await second).toBe("c");
  });
});

function pendingCallback() {
  return { state: "the-state", resolve: vi.fn(), reject: vi.fn() } satisfies PendingAuthCallback as PendingAuthCallback & {
    resolve: ReturnType<typeof vi.fn>;
    reject: ReturnType<typeof vi.fn>;
  };
}

describe("handleMobileRedirectCallback", () => {
  it("should_resolveWithCode_when_stateMatchesAndCodePresent", () => {
    const pending = pendingCallback();

    handleMobileRedirectCallback(pending, { code: "the-code", state: "the-state" });

    expect(pending.resolve).toHaveBeenCalledWith("the-code");
    expect(pending.reject).not.toHaveBeenCalled();
  });

  it("should_reject_when_stateDoesNotMatch", () => {
    const pending = pendingCallback();

    handleMobileRedirectCallback(pending, { code: "the-code", state: "wrong-state" });

    expect(pending.reject).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining("state mismatch") }));
  });

  it("should_reject_when_errorParamPresent", () => {
    const pending = pendingCallback();

    handleMobileRedirectCallback(pending, { error: "access_denied", state: "the-state" });

    expect(pending.reject).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining("access_denied") }));
  });

  it("should_reject_when_codeMissing", () => {
    const pending = pendingCallback();

    handleMobileRedirectCallback(pending, { state: "the-state" });

    expect(pending.reject).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining("no authorization code") }));
  });

  it("should_doNothing_when_noCallbackIsPending", () => {
    expect(() => handleMobileRedirectCallback(null, { code: "x", state: "y" })).not.toThrow();
  });
});
