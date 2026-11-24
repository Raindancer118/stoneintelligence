import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/svelte";
import App from "../src/App.svelte";
import { completeLogin, getUser } from "../src/lib/auth";
vi.mock("../src/lib/auth", () => ({
  getUser: vi.fn(), completeLogin: vi.fn(), login: vi.fn(), logout: vi.fn(), preferredUsername: () => "Tom",
}));
beforeEach(() => vi.resetAllMocks());
afterEach(() => { cleanup(); window.history.replaceState({}, "", "/"); });

describe("login callback", () => {
  it("leaves /callback and shows the login page with a message when the code exchange fails", async () => {
    window.history.replaceState({}, "", "/callback?code=used&state=gone");
    vi.mocked(completeLogin).mockRejectedValue(new Error("No matching state found in storage"));
    vi.mocked(getUser).mockResolvedValue(null);
    render(App);
    await screen.findByRole("button", { name: "Mit Authentik anmelden" });
    expect(screen.getByRole("alert").textContent).toContain("Anmeldung");
    expect(window.location.pathname).toBe("/");
    expect(window.location.search).toBe("");
  });
});
