import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import InviteLanding from "../src/lib/components/InviteLanding.svelte";
import { api } from "../src/lib/api";
vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: { describeInvitation: vi.fn(), acceptInvitation: vi.fn() } }));
const pending = {
  state: "PENDING", vaultName: "Team-Notizen", invitedBy: "tom", maskedEmail: "n***@example.org", access: "EDIT",
  expiresAt: "2026-10-07T10:00:00Z", enrollmentUrl: "https://portal.example/if/flow/x/?itoken=1",
};
beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);

describe("Einladungsseite", () => {
  it("explains the invitation and offers sign-in and account creation when signed out", async () => {
    vi.mocked(api.describeInvitation).mockResolvedValue(pending);
    const onLogin = vi.fn();
    render(InviteLanding, { token: "tok", signedIn: false, onLogin, onJoined: vi.fn() });

    await screen.findByRole("heading", { name: "tom lädt dich zu „Team-Notizen“ ein" });
    const create = screen.getByRole("link", { name: "Neues Konto erstellen" });
    expect(create.getAttribute("href")).toBe(pending.enrollmentUrl);
    expect(create.getAttribute("rel")).toContain("noopener");
    await fireEvent.click(screen.getByRole("button", { name: "Ich habe schon ein Konto – anmelden" }));
    expect(onLogin).toHaveBeenCalled();
  });

  it("accepts with one click when signed in", async () => {
    vi.mocked(api.describeInvitation).mockResolvedValue(pending);
    vi.mocked(api.acceptInvitation).mockResolvedValue({ vaultId: "v1", vaultName: "Team-Notizen" });
    const onJoined = vi.fn();
    render(InviteLanding, { token: "tok", signedIn: true, onLogin: vi.fn(), onJoined });

    await fireEvent.click(await screen.findByRole("button", { name: "Einladung annehmen" }));

    await waitFor(() => expect(onJoined).toHaveBeenCalledWith("v1"));
    expect(api.acceptInvitation).toHaveBeenCalledWith("tok");
  });

  it.each([
    ["EXPIRED", "Diese Einladung ist abgelaufen."],
    ["ACCEPTED", "Diese Einladung wurde bereits angenommen."],
    ["REVOKED", "Diese Einladung wurde zurückgezogen."],
  ])("says plainly when an invitation is %s", async (state, message) => {
    vi.mocked(api.describeInvitation).mockResolvedValue({ ...pending, state });
    render(InviteLanding, { token: "tok", signedIn: true, onLogin: vi.fn(), onJoined: vi.fn() });

    await screen.findByText(message, { exact: false });
    expect(screen.queryByRole("button", { name: "Einladung annehmen" })).toBeNull();
  });

  it("reports an invalid link", async () => {
    vi.mocked(api.describeInvitation).mockRejectedValue(new Error("Dieser Einladungslink ist ungültig."));
    render(InviteLanding, { token: "tok", signedIn: false, onLogin: vi.fn(), onJoined: vi.fn() });

    await screen.findByText("Dieser Einladungslink ist ungültig.");
  });
});
