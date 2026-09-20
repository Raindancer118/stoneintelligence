import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import NotesWorkspace from "../src/lib/components/NotesWorkspace.svelte";
import { api } from "../src/lib/api";
vi.mock("../src/lib/api", () => ({ api: { listNotes: vi.fn(), permissions: vi.fn(), createNote: vi.fn(), noteContent: vi.fn() } }));
const vault = { id: "vault", name: "Team", createdAt: "2026-09-19" };
const note = { id: "note", vaultId: "vault", path: "Projects/Plan.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-19T00:00:00Z" };
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.permissions).mockResolvedValue(["READ", "CREATE"]);
  vi.mocked(api.listNotes).mockResolvedValue({ epochId: "epoch", notes: [note], complete: true, nextCursor: null });
});
afterEach(cleanup);
describe("notes workspace", () => {
  it("searches loaded paths and clearly explains empty search results", async () => {
    render(NotesWorkspace, { vault, onDirtyChange: vi.fn() });
    await screen.findByRole("button", { name: /Plan/ });
    await fireEvent.input(screen.getByRole("searchbox"), { target: { value: "missing" } });
    await screen.findByText("Keine passenden Notizen.");
    expect(screen.queryByRole("button", { name: /Plan/ })).toBeNull();
  });
  it("continues pagination even when an ACL-filtered page contains no notes", async () => {
    vi.mocked(api.listNotes).mockResolvedValueOnce({ epochId: "epoch", notes: [], complete: false, nextCursor: "next" })
      .mockResolvedValueOnce({ epochId: "epoch", notes: [note], complete: true, nextCursor: null });
    render(NotesWorkspace, { vault, onDirtyChange: vi.fn() });
    await fireEvent.click(await screen.findByRole("button", { name: "Weitere Notizen laden" }));
    await screen.findByRole("button", { name: /Plan/ });
    expect(api.listNotes).toHaveBeenLastCalledWith("vault", "next");
  });
});
