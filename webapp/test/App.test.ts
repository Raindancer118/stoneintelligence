import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import App from "../src/App.svelte";
import { completeLogin, getUser, login } from "../src/lib/auth";
import { api } from "../src/lib/api";
vi.mock("../src/lib/auth", () => ({
  getUser: vi.fn(), completeLogin: vi.fn(), login: vi.fn(), logout: vi.fn(),
  preferredUsername: () => "Tom",
}));
vi.mock("../src/lib/api", () => ({ api: {
  listVaults: vi.fn(), permissions: vi.fn(), listNotes: vi.fn(), describeInvitation: vi.fn(), acceptInvitation: vi.fn(),
  aiServices: vi.fn(), listAiJobs: vi.fn(), listChangeSets: vi.fn(),
} }));
beforeEach(() => { vi.resetAllMocks(); window.history.replaceState({}, "", "/"); });
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
    vi.mocked(api.permissions).mockResolvedValue(["READ"]);
    vi.mocked(api.listNotes).mockResolvedValue({ epochId: "epoch", notes: [], complete: true, nextCursor: null });
    render(App);
    await screen.findByRole("heading", { name: "My notes", level: 1 });
    expect(api.listVaults).toHaveBeenCalledTimes(1);
  });
});

describe("KI-Bereich", () => {
  it("opens the AI area of the selected vault", async () => {
    vi.mocked(getUser).mockResolvedValue({ profile: { sub: "tom" } } as Awaited<ReturnType<typeof getUser>>);
    vi.mocked(api.listVaults).mockResolvedValue([{ id: "vault", name: "My notes", createdAt: "" }]);
    vi.mocked(api.permissions).mockResolvedValue(["READ", "CREATE"]);
    vi.mocked(api.listNotes).mockResolvedValue({ epochId: "epoch", notes: [], complete: true, nextCursor: null });
    vi.mocked(api.aiServices).mockResolvedValue([{ id: "gemini", name: "Gemini", levels: [1] }]);
    vi.mocked(api.listAiJobs).mockResolvedValue([]);
    vi.mocked(api.listChangeSets).mockResolvedValue([]);
    render(App);

    await fireEvent.click(await screen.findByRole("button", { name: "KI-Wissen" }));

    await screen.findByRole("heading", { name: "Wissen aus Dokumenten" });
    expect(screen.getByRole("button", { name: "KI-Wissen" }).getAttribute("aria-pressed")).toBe("true");
    expect(api.listAiJobs).toHaveBeenCalledWith("vault");
  });
});

describe("invitation links", () => {
  const invitation = {
    state: "PENDING", vaultName: "Team-Notizen", invitedBy: "tom", maskedEmail: "n***@example.org", access: "EDIT",
    expiresAt: "2026-10-07T10:00:00Z", enrollmentUrl: null,
  } as const;

  it("shows the invitation without requiring a login, and signs in back to it", async () => {
    window.history.replaceState({}, "", "/invite/tok123");
    vi.mocked(getUser).mockResolvedValue(null);
    vi.mocked(api.describeInvitation).mockResolvedValue(invitation);
    render(App);

    await screen.findByRole("heading", { name: "tom lädt dich zu „Team-Notizen“ ein" });
    await fireEvent.click(screen.getByRole("button", { name: "Ich habe schon ein Konto – anmelden" }));
    expect(login).toHaveBeenCalledWith("/invite/tok123");
  });

  it("opens the joined vault after accepting", async () => {
    window.history.replaceState({}, "", "/invite/tok123");
    vi.mocked(getUser).mockResolvedValue({ profile: { sub: "neu" } } as Awaited<ReturnType<typeof getUser>>);
    vi.mocked(api.describeInvitation).mockResolvedValue(invitation);
    vi.mocked(api.acceptInvitation).mockResolvedValue({ vaultId: "v1", vaultName: "Team-Notizen" });
    vi.mocked(api.listVaults).mockResolvedValue([{ id: "v1", name: "Team-Notizen", createdAt: "" }]);
    vi.mocked(api.permissions).mockResolvedValue(["READ"]);
    vi.mocked(api.listNotes).mockResolvedValue({ epochId: "e", notes: [], complete: true, nextCursor: null });
    render(App);

    await fireEvent.click(await screen.findByRole("button", { name: "Einladung annehmen" }));

    await screen.findByRole("heading", { name: "Team-Notizen", level: 1 });
    expect(window.location.pathname).toBe("/");
    expect(screen.getByRole("button", { name: "In Obsidian" }).getAttribute("aria-pressed")).toBe("true");
  });

  it("returns to the invitation after the login callback", async () => {
    window.history.replaceState({}, "", "/callback?code=x");
    vi.mocked(completeLogin).mockResolvedValue({ state: { returnTo: "/invite/tok123" } } as Awaited<ReturnType<typeof completeLogin>>);
    vi.mocked(getUser).mockResolvedValue({ profile: { sub: "neu" } } as Awaited<ReturnType<typeof getUser>>);
    vi.mocked(api.describeInvitation).mockResolvedValue(invitation);
    render(App);

    await screen.findByRole("button", { name: "Einladung annehmen" });
    await waitFor(() => expect(window.location.pathname).toBe("/invite/tok123"));
  });
});

describe("setup page", () => {
  it("is reachable without login", async () => {
    window.history.replaceState({}, "", "/setup");
    vi.mocked(getUser).mockResolvedValue(null);
    render(App);

    await screen.findByRole("heading", { name: "Obsidian einrichten", level: 1 });
    expect(api.listVaults).not.toHaveBeenCalled();
  });

  it("shows the connect links for the signed-in person's vaults", async () => {
    window.history.replaceState({}, "", "/setup");
    vi.mocked(getUser).mockResolvedValue({ profile: { sub: "tom" } } as Awaited<ReturnType<typeof getUser>>);
    vi.mocked(api.listVaults).mockResolvedValue([{ id: "2f719285-2483-4e59-89e0-334af2813a70", name: "Team", createdAt: "" }]);
    render(App);

    await screen.findByRole("link", { name: "Mit „Team“ verbinden" });
  });

  it("links to the setup from the dashboard", async () => {
    vi.mocked(getUser).mockResolvedValue({ profile: { sub: "tom" } } as Awaited<ReturnType<typeof getUser>>);
    vi.mocked(api.listVaults).mockResolvedValue([{ id: "v", name: "My notes", createdAt: "" }]);
    vi.mocked(api.permissions).mockResolvedValue(["READ"]);
    vi.mocked(api.listNotes).mockResolvedValue({ epochId: "e", notes: [], complete: true, nextCursor: null });
    render(App);

    await fireEvent.click(await screen.findByRole("link", { name: "Obsidian einrichten" }));

    await screen.findByRole("heading", { name: "Obsidian einrichten", level: 1 });
    expect(window.location.pathname).toBe("/setup");
  });

  it("sets up Obsidian for the selected vault right from the vault", async () => {
    vi.mocked(getUser).mockResolvedValue({ profile: { sub: "tom" } } as Awaited<ReturnType<typeof getUser>>);
    vi.mocked(api.listVaults).mockResolvedValue([
      { id: "2f719285-2483-4e59-89e0-334af2813a70", name: "My notes", createdAt: "" },
      { id: "11111111-2222-4333-8444-555555555555", name: "Other", createdAt: "" },
    ]);
    vi.mocked(api.permissions).mockResolvedValue(["READ"]);
    vi.mocked(api.listNotes).mockResolvedValue({ epochId: "e", notes: [], complete: true, nextCursor: null });
    render(App);

    await fireEvent.click(await screen.findByRole("button", { name: "In Obsidian" }));

    const links = await screen.findAllByRole("link", { name: "Mit „My notes“ verbinden" });
    expect(links[0].getAttribute("href")).toContain("stoneVault=2f719285-2483-4e59-89e0-334af2813a70");
    expect(screen.queryByRole("link", { name: "Mit „Other“ verbinden" })).toBeNull();
  });
});

