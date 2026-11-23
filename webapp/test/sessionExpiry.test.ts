import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import App from "../src/App.svelte";
import { getAccessToken, getUser, login } from "../src/lib/auth";
import { api } from "../src/lib/api";
import { notifySessionExpired, onSessionExpired } from "../src/lib/session";

vi.mock("../src/lib/auth", () => ({
  getUser: vi.fn(), completeLogin: vi.fn(), login: vi.fn(), logout: vi.fn(), getAccessToken: vi.fn(),
  preferredUsername: () => "Tom",
}));

beforeEach(() => { vi.resetAllMocks(); window.history.replaceState({}, "", "/"); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("API session expiry", () => {
  it("tells the app when the server answers 401, so it can offer a new login", async () => {
    vi.mocked(getAccessToken).mockResolvedValue("t");
    const expired = vi.fn();
    const off = onSessionExpired(expired);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    await expect(api.listVaults()).rejects.toMatchObject({ status: 401 });
    expect(expired).toHaveBeenCalledTimes(1);
    off();
  });

  it("reports a lost login as a readable 401 instead of a raw internal error", async () => {
    vi.mocked(getAccessToken).mockRejectedValue(new Error("nicht angemeldet"));
    const expired = vi.fn();
    const off = onSessionExpired(expired);
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    await expect(api.listVaults()).rejects.toMatchObject({ status: 401, message: expect.stringContaining("Anmeldung") });
    expect(expired).toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
    off();
  });
});

describe("session expiry mid-session", () => {
  it("offers a re-login banner without dropping the page", async () => {
    vi.mocked(getUser).mockResolvedValue({ profile: { sub: "tom" } } as Awaited<ReturnType<typeof getUser>>);
    vi.spyOn(api, "listVaults").mockResolvedValue([{ id: "vault", name: "My notes", createdAt: "" }]);
    vi.spyOn(api, "permissions").mockResolvedValue(["READ"]);
    vi.spyOn(api, "folderChildren").mockResolvedValue([]);
    vi.spyOn(api, "listFolder").mockResolvedValue({ epochId: "e", notes: [], complete: true, nextCursor: null });
    render(App);
    await screen.findByRole("heading", { name: "My notes", level: 1 });
    notifySessionExpired();
    const text = await screen.findByText(/Anmeldung ist abgelaufen/);
    expect(text.closest("[role=alert]")).not.toBeNull();
    await fireEvent.click(screen.getByRole("button", { name: "Erneut anmelden" }));
    expect(login).toHaveBeenCalled();
  });
});
