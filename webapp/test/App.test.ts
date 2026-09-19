import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/svelte";
import App from "../src/App.svelte";
import { getUser } from "../src/lib/auth";
import { api } from "../src/lib/api";
vi.mock("../src/lib/auth", () => ({
  getUser: vi.fn(), completeLogin: vi.fn(), login: vi.fn(), logout: vi.fn(),
  preferredUsername: () => "Tom",
}));
vi.mock("../src/lib/api", () => ({ api: { listVaults: vi.fn() } }));
beforeEach(() => { vi.resetAllMocks(); });
afterEach(cleanup);
describe("dashboard startup", () => {
  it("shows login without querying vaults when no valid session exists", async () => {
    vi.mocked(getUser).mockResolvedValue(null);
    render(App);
    await screen.findByRole("button", { name: "Mit Authentik anmelden" });
    expect(api.listVaults).not.toHaveBeenCalled();
  });
  it("mounts the dashboard and loads the vault list", async () => {
    vi.mocked(getUser).mockResolvedValue({ profile: { sub: "tom" } } as Awaited<ReturnType<typeof getUser>>);
    vi.mocked(api.listVaults).mockResolvedValue([{ id: "vault", name: "My notes", createdAt: "" }]);
    render(App);
    await screen.findByText("My notes");
    expect(api.listVaults).toHaveBeenCalledTimes(1);
  });
});
