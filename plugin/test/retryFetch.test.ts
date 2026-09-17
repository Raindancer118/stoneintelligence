import { describe, expect, it, vi } from "vitest";
import { withRateLimitRetry } from "../src/sync/retryFetch";

function response(status: number): Response {
  return { ok: status >= 200 && status < 300, status } as Response;
}

describe("withRateLimitRetry", () => {
  it("should_returnImmediately_when_firstResponseIsNotRateLimited", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response(200));
    const wrapped = withRateLimitRetry(fetchImpl, { sleep: vi.fn() });

    const result = await wrapped("https://example.invalid");

    expect(result.status).toBe(200);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("should_retryAfterBackoff_when_responseIs429_then_succeed", async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(429))
      .mockResolvedValueOnce(response(200));
    const sleep = vi.fn().mockResolvedValue(undefined);
    const wrapped = withRateLimitRetry(fetchImpl, { sleep });

    const result = await wrapped("https://example.invalid");

    expect(result.status).toBe(200);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(sleep).toHaveBeenCalledTimes(1);
  });

  it("should_useExponentialBackoff_betweenRetries", async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(429))
      .mockResolvedValueOnce(response(429))
      .mockResolvedValueOnce(response(200));
    const sleep = vi.fn().mockResolvedValue(undefined);
    const wrapped = withRateLimitRetry(fetchImpl, { sleep, initialDelayMs: 100 });

    await wrapped("https://example.invalid");

    expect(sleep).toHaveBeenNthCalledWith(1, 100);
    expect(sleep).toHaveBeenNthCalledWith(2, 200);
  });

  it("should_stopRetrying_after_maxRetries_and_returnLastResponse", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response(429));
    const sleep = vi.fn().mockResolvedValue(undefined);
    const wrapped = withRateLimitRetry(fetchImpl, { sleep, maxRetries: 3 });

    const result = await wrapped("https://example.invalid");

    expect(result.status).toBe(429);
    expect(fetchImpl).toHaveBeenCalledTimes(4);
    expect(sleep).toHaveBeenCalledTimes(3);
  });

  it("should_passThroughInputAndInit_toWrappedFetch", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response(200));
    const wrapped = withRateLimitRetry(fetchImpl, { sleep: vi.fn() });

    await wrapped("https://example.invalid", { method: "POST", body: "x" });

    expect(fetchImpl).toHaveBeenCalledWith("https://example.invalid", { method: "POST", body: "x" });
  });
});
