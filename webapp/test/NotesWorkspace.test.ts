import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import NotesWorkspace from "../src/lib/components/NotesWorkspace.svelte";
import { api } from "../src/lib/api";
vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: { listNotes: vi.fn(), permissions: vi.fn(), createNote: vi.fn(), noteContent: vi.fn(), fileBlob: vi.fn(), similarNotes: vi.fn(), noteHistory: vi.fn() } }));
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
  // Dateien (ADR 0009) stehen mit in der Liste - mit Endung und Art, geoeffnet in der Dateiansicht.
  it("lists files next to notes and opens them in the file view", async () => {
    const pdf = { id: "f1", vaultId: "vault", path: "Anhänge/Skript.pdf", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-23T00:00:00Z",
      kind: "FILE" as const, sha256: "ab", size: 1024, revision: 1 };
    vi.mocked(api.listNotes).mockResolvedValue({ epochId: "epoch", notes: [note, pdf], complete: true, nextCursor: null });
    vi.mocked(api.fileBlob).mockResolvedValue(new Blob(["%PDF"], { type: "application/pdf" }));
    URL.createObjectURL = vi.fn(() => "blob:x");
    URL.revokeObjectURL = vi.fn();
    render(NotesWorkspace, { vault, onDirtyChange: vi.fn() });

    await screen.findByText("1 Notiz, 1 Datei geladen");
    await fireEvent.click(await screen.findByRole("button", { name: /Skript\.pdf.*PDF/ }));

    await screen.findByRole("link", { name: "Herunterladen" });
  });
  it("shows similar notes and opens one with a click", async () => {
    const other = { id: "other", vaultId: "vault", path: "Biologie/Photosynthese.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-20T00:00:00Z" };
    vi.mocked(api.listNotes).mockResolvedValue({ epochId: "epoch", notes: [note, other], complete: true, nextCursor: null });
    vi.mocked(api.noteContent).mockResolvedValue({ revision: 0, updates: [] });
    vi.mocked(api.similarNotes).mockResolvedValue([{ noteId: "other", path: "Biologie/Photosynthese.md", heading: "Licht", similarity: 0.91 }]);
    render(NotesWorkspace, { vault, onDirtyChange: vi.fn() });
    await fireEvent.click(await screen.findByRole("button", { name: /Plan/ }));

    await fireEvent.click(await screen.findByRole("button", { name: "Ähnliche Notizen" }));
    expect(await screen.findByText(/91 % ähnlich · „Licht“/)).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Photosynthese" }));

    expect(api.similarNotes).toHaveBeenCalledWith("vault", "note");
    expect(await screen.findByRole("heading", { name: "Photosynthese" })).toBeTruthy();
  });
});
