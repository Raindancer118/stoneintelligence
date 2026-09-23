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

  it("should_exposeResponseHeaders_caseInsensitively", async () => {
    requestUrlMock.mockResolvedValue({
      status: 429, json: null, text: "", headers: { "Retry-After": "3" },
    });

    const response = await obsidianFetch("https://example.com/api");

    expect(response.headers.get("retry-after")).toBe("3");
    expect(response.headers.get("Retry-After")).toBe("3");
  });

  it("should_returnNull_forMissingHeader", async () => {
    requestUrlMock.mockResolvedValue({ status: 200, json: {}, text: "{}", headers: {} });

    const response = await obsidianFetch("https://example.com/api");

    expect(response.headers.get("retry-after")).toBeNull();
  });

  // Dateien (PDFs, Bilder) gehen als Bytes hin und zurueck - nicht als Text.
  it("should_sendBinaryBodies_andReadBinaryResponses", async () => {
    const bytes = new Uint8Array([37, 80, 68, 70, 0, 255]);
    requestUrlMock.mockResolvedValue({ status: 200, json: null, text: "", arrayBuffer: bytes.buffer });

    const response = await obsidianFetch("https://example.com/file", { method: "PUT", body: bytes });

    const sent = requestUrlMock.mock.calls[0][0].body as ArrayBuffer;
    expect(new Uint8Array(sent)).toEqual(bytes);
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(bytes);
  });
});

