import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import VaultDetail from "../src/lib/components/VaultDetail.svelte";
import { api } from "../src/lib/api";
vi.mock("../src/lib/api", () => ({ api: {
  listRoles: vi.fn(), listGroups: vi.fn(), listPathRules: vi.fn(), createGroup: vi.fn(), createRole: vi.fn(), createPathRule: vi.fn(),
  listInvitations: vi.fn(), searchPeople: vi.fn(),
} }));
const group = { id: "group", name: "Team", memberSubjects: [], roleIds: [] };
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.listRoles).mockResolvedValue([]);
  vi.mocked(api.listGroups).mockResolvedValue([group]);
  vi.mocked(api.listPathRules).mockResolvedValue([]);
  vi.mocked(api.listInvitations).mockResolvedValue([]);
});
afterEach(cleanup);
describe("vault detail loading", () => {
  it.each([
    { placeholder: "Gruppenname", button: "Gruppe anlegen", refresh: "listGroups" },
    { placeholder: "Rollenname", button: "Rolle anlegen", refresh: "listRoles" },
    { placeholder: "Pfad-Präfix", button: "Regel anlegen", refresh: "listPathRules" },
  ] as const)("reloads only $refresh after $button", async ({ placeholder, button, refresh }) => {
    render(VaultDetail, { vault: { id: "vault", name: "Notes", createdAt: "" } });
    await screen.findByText("Team");
    await fireEvent.input(screen.getByPlaceholderText(placeholder), { target: { value: "New team" } });
    if (refresh === "listRoles") await fireEvent.click(screen.getByRole("checkbox", { name: "READ" }));
    await fireEvent.click(screen.getByRole("button", { name: button }));
    await waitFor(() => expect(api[refresh]).toHaveBeenCalledTimes(2));
    for (const resource of ["listRoles", "listGroups", "listPathRules"] as const) {
      expect(api[resource]).toHaveBeenCalledTimes(resource === refresh ? 2 : 1);
    }
  });
});
