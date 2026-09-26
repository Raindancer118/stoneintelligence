import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import VaultDetail from "../src/lib/components/VaultDetail.svelte";
import { api } from "../src/lib/api";
vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: {
  listRoles: vi.fn(), listGroups: vi.fn(), listMembers: vi.fn(), createGroup: vi.fn(), createRole: vi.fn(),
  listInvitations: vi.fn(), searchPeople: vi.fn(), removeFromVault: vi.fn(), deleteRole: vi.fn(), updateRole: vi.fn(),
  renameGroup: vi.fn(), deleteGroup: vi.fn(),
} }));
const group = { id: "group", name: "Team", memberSubjects: [], roleIds: [] };
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.listRoles).mockResolvedValue([]);
  vi.mocked(api.listGroups).mockResolvedValue([group]);
  vi.mocked(api.listMembers).mockResolvedValue([
    { subject: "tom", groups: [{ id: "group", name: "Team" }], permissions: ["READ", "MANAGE"] },
    { subject: "ben", groups: [], permissions: [] },
  ]);
  vi.mocked(api.listInvitations).mockResolvedValue([]);
});
afterEach(cleanup);
describe("vault detail loading", () => {
  it.each([
    { placeholder: "Gruppenname", button: "Gruppe anlegen", refresh: "listGroups" },
    { placeholder: "Rollenname", button: "Rolle anlegen", refresh: "listRoles" },
  ] as const)("reloads only $refresh after $button", async ({ placeholder, button, refresh }) => {
    render(VaultDetail, { vault: { id: "vault", name: "Notes", createdAt: "" }, me: "tom" });
    await screen.findByLabelText("Name der Gruppe Team");
    await fireEvent.input(screen.getByPlaceholderText(placeholder), { target: { value: "New team" } });
    if (refresh === "listRoles") await fireEvent.click(screen.getByRole("checkbox", { name: "READ" }));
    await fireEvent.click(screen.getByRole("button", { name: button }));
    await waitFor(() => expect(api[refresh]).toHaveBeenCalledTimes(2));
    for (const resource of ["listRoles", "listGroups", "listMembers"] as const) {
      expect(api[resource]).toHaveBeenCalledTimes(resource === refresh ? 2 : 1);
    }
  });
});

describe("vault members and management", () => {
  const vault = { id: "vault", name: "Notes", createdAt: "" };

  it("lists members with their rights and lets you leave or remove others after asking", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.mocked(api.removeFromVault).mockResolvedValue(undefined);
    render(VaultDetail, { vault, me: "tom" });

    expect(await screen.findByText("tom (du)")).toBeTruthy();
    expect(screen.getByText("Lesen, Verwalten · Team")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "entfernen" }));
    await waitFor(() => expect(api.removeFromVault).toHaveBeenCalledWith("vault", "ben"));
    expect(screen.getByRole("button", { name: "Vault verlassen" })).toBeTruthy();
  });

  it("explains in plain words why the server refused", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const { ApiError } = await vi.importActual<typeof import("../src/lib/api")>("../src/lib/api");
    vi.mocked(api.deleteGroup).mockRejectedValue(new ApiError(409));
    render(VaultDetail, { vault, me: "tom" });

    await fireEvent.click(await screen.findByRole("button", { name: "Gruppe löschen" }));

    expect(await screen.findByText(/niemand mehr den Vault verwalten/)).toBeTruthy();
  });
});
