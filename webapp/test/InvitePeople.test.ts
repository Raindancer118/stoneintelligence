import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import InvitePeople from "../src/lib/components/InvitePeople.svelte";
import { api } from "../src/lib/api";
vi.mock("../src/lib/api", () => ({ api: {
  searchPeople: vi.fn(), addPerson: vi.fn(), inviteByEmail: vi.fn(), listInvitations: vi.fn(), revokeInvitation: vi.fn(),
} }));
const vault = { id: "vault", name: "Team", createdAt: "" };
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.listInvitations).mockResolvedValue([]);
  vi.mocked(api.searchPeople).mockResolvedValue([]);
});
afterEach(cleanup);

async function type(value: string) {
  await fireEvent.input(screen.getByLabelText("Name oder E-Mail-Adresse"), { target: { value } });
}

describe("Personen einladen", () => {
  it("finds existing accounts while typing and adds one with a click", async () => {
    vi.mocked(api.searchPeople).mockResolvedValue([
      { username: "anna", name: "Anna Arendt", maskedEmail: "a***@example.org", alreadyMember: false },
      { username: "ben", name: "Ben Becker", maskedEmail: "b***@example.org", alreadyMember: true },
    ]);
    vi.mocked(api.addPerson).mockResolvedValue({ status: "ADDED", displayName: "Anna Arendt" });
    render(InvitePeople, { vault });

    await type("an");

    await screen.findByText("Anna Arendt");
    expect(screen.getByText("bereits Mitglied")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Anna Arendt hinzufügen" }));
    await waitFor(() => expect(api.addPerson).toHaveBeenCalledWith("vault", "anna", "EDIT"));
    await screen.findByText("Anna Arendt ist jetzt Mitglied.");
  });

  it("offers an email invitation for addresses without an account, with the chosen access", async () => {
    vi.mocked(api.inviteByEmail).mockResolvedValue({ status: "INVITED", displayName: "neu@example.org" });
    render(InvitePeople, { vault });

    await type("neu@example.org");
    await fireEvent.change(screen.getByLabelText("Rechte"), { target: { value: "READ" } });
    await fireEvent.click(await screen.findByRole("button", { name: "Einladung an neu@example.org senden" }));

    await waitFor(() => expect(api.inviteByEmail).toHaveBeenCalledWith("vault", "neu@example.org", "READ"));
    await screen.findByText("Einladung an neu@example.org verschickt.");
    expect(api.listInvitations).toHaveBeenCalledTimes(2);
  });

  it("does not offer an email invitation for something that is not an address", async () => {
    render(InvitePeople, { vault });

    await type("anna");

    await waitFor(() => expect(api.searchPeople).toHaveBeenCalled());
    expect(screen.queryByRole("button", { name: /Einladung an/ })).toBeNull();
  });

  it("lists pending invitations and withdraws one", async () => {
    vi.mocked(api.listInvitations).mockResolvedValueOnce([
      { id: "inv-1", email: "neu@example.org", access: "EDIT", invitedBy: "tom", createdAt: "2026-09-23T10:00:00Z", expiresAt: "2026-10-07T10:00:00Z" },
    ]).mockResolvedValueOnce([]);
    render(InvitePeople, { vault });

    await fireEvent.click(await screen.findByRole("button", { name: "Einladung an neu@example.org zurückziehen" }));

    await waitFor(() => expect(api.revokeInvitation).toHaveBeenCalledWith("vault", "inv-1"));
    await waitFor(() => expect(screen.queryByText("neu@example.org")).toBeNull());
  });

  it("shows the server's explanation when inviting fails", async () => {
    vi.mocked(api.inviteByEmail).mockRejectedValue(new Error("Die E-Mail konnte nicht zugestellt werden."));
    render(InvitePeople, { vault });

    await type("neu@example.org");
    await fireEvent.click(await screen.findByRole("button", { name: "Einladung an neu@example.org senden" }));

    await screen.findByText("Die E-Mail konnte nicht zugestellt werden.");
  });
});
