import { beforeEach, describe, expect, it, vi } from "vitest";

const requestUrlMock = vi.fn();
vi.mock("obsidian", () => ({ requestUrl: (...args: unknown[]) => requestUrlMock(...args) }));

// Import AFTER the mock is registered, so obsidianFetch picks up the mocked requestUrl.
const { obsidianFetch } = await import("../src/sync/obsidianFetch");

describe("obsidianFetch", () => {
  beforeEach(() => {
    requestUrlMock.mockReset();
  });

  it("should_defaultToGet_andPassUrlThrough", async () => {
    requestUrlMock.mockResolvedValue({ status: 200, json: { hello: "world" }, text: "{}" });

    await obsidianFetch("https://example.com/api");

    expect(requestUrlMock).toHaveBeenCalledWith(
      expect.objectContaining({ url: "https://example.com/api", method: "GET", throw: false }),
    );
  });

  it("should_passMethodHeadersAndBody_throughToRequestUrl", async () => {
    requestUrlMock.mockResolvedValue({ status: 200, json: {}, text: "{}" });

    await obsidianFetch("https://example.com/api", {
      method: "POST",
      headers: { Authorization: "Bearer token" },
      body: JSON.stringify({ name: "vault" }),
    });

    expect(requestUrlMock).toHaveBeenCalledWith(
      expect.objectContaining({
        method: "POST",
        headers: { Authorization: "Bearer token" },
        body: JSON.stringify({ name: "vault" }),
      }),
    );
  });

  it("should_reportOk_forSuccessStatus", async () => {
    requestUrlMock.mockResolvedValue({ status: 200, json: { id: "note-1" }, text: "{}" });

    const response = await obsidianFetch("https://example.com/api");

    expect(response.ok).toBe(true);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ id: "note-1" });
  });

  it("should_reportNotOk_forErrorStatus_withoutThrowing", async () => {
    requestUrlMock.mockResolvedValue({ status: 403, json: null, text: "" });

    const response = await obsidianFetch("https://example.com/api");

    expect(response.ok).toBe(false);
    expect(response.status).toBe(403);
  });
});
