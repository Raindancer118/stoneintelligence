import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import QuickJump from "../src/lib/components/QuickJump.svelte";
import { api } from "../src/lib/api";
vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: { searchNotes: vi.fn() } }));
const note = { id: "n1", vaultId: "vault", path: "Studium/Grenzwerte.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-19T00:00:00Z" };
const areas = [{ id: "notes", label: "Notizen" }, { id: "ai", label: "KI-Wissen" }];
beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.searchNotes).mockResolvedValue({ notes: [note], truncated: false }); });
afterEach(cleanup);
describe("quick jump", () => {
  it("finds notes through the server and opens the chosen one", async () => {
    const onNote = vi.fn();
    render(QuickJump, { vaultId: "vault", areas, onNote, onArea: vi.fn(), onClose: vi.fn() });
    await fireEvent.input(screen.getByRole("searchbox", { name: "Ziel suchen" }), { target: { value: "grenz" } });
    await fireEvent.click(await screen.findByRole("button", { name: /Grenzwerte/ }));
    expect(api.searchNotes).toHaveBeenCalledWith("vault", "grenz", 20);
    expect(onNote).toHaveBeenCalledWith(note);
  });
  it("matches areas locally without asking the server for an empty query", async () => {
    const onArea = vi.fn();
    render(QuickJump, { vaultId: "vault", areas, onNote: vi.fn(), onArea, onClose: vi.fn() });
    await fireEvent.click(screen.getByRole("button", { name: /KI-Wissen/ }));
    expect(onArea).toHaveBeenCalledWith("ai");
    expect(api.searchNotes).not.toHaveBeenCalled();
  });
});
