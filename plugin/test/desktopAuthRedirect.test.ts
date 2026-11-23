import { describe, expect, it } from "vitest";
import { awaitDesktopRedirectCode, cancelDesktopRedirect, OIDC_REDIRECT_PORT } from "../src/sync/desktopAuthRedirect";

const callback = (query: string) => fetch(`http://127.0.0.1:${OIDC_REDIRECT_PORT}/callback?${query}`);
const listening = () => new Promise((resolve) => setTimeout(resolve, 50));

describe("awaitDesktopRedirectCode", () => {
  it("should_resolveWithTheCode", async () => {
    const code = awaitDesktopRedirectCode("s1");
    await listening();
    await callback("code=abc&state=s1");
    expect(await code).toBe("abc");
  });

  it("should_giveUpAndFreeThePort_whenTheLoginIsAbandoned", async () => {
    await expect(awaitDesktopRedirectCode("s1", 100)).rejects.toThrow(/timed out/);
    const next = awaitDesktopRedirectCode("s2");
    await listening();
    await callback("code=x&state=s2");
    expect(await next).toBe("x");
  });

  it("should_supersedeAnEarlierPendingLogin_insteadOfFailingWithAddressInUse", async () => {
    const firstOutcome = awaitDesktopRedirectCode("s1").catch((e: Error) => e.message);
    await listening();
    const second = awaitDesktopRedirectCode("s2");
    await listening();
    await callback("code=y&state=s2");
    expect(await second).toBe("y");
    expect(await firstOutcome).toMatch(/cancel/);
  });

  it("should_rejectAndFreeThePort_whenCancelledOnUnload", async () => {
    const outcome = awaitDesktopRedirectCode("s1").catch((e: Error) => e.message);
    await listening();
    cancelDesktopRedirect();
    expect(await outcome).toMatch(/cancel/);
  });
});
