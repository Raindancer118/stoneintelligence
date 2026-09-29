import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import NotesWorkspace from "../src/lib/components/NotesWorkspace.svelte";
import { api } from "../src/lib/api";
vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: { listNotes: vi.fn(), listFolder: vi.fn(), folderChildren: vi.fn(), permissions: vi.fn(), createNote: vi.fn(), noteContent: vi.fn(), fileBlob: vi.fn(), similarNotes: vi.fn(), noteHistory: vi.fn(), searchNotes: vi.fn() } }));
const vault = { id: "vault", name: "Team", createdAt: "2026-09-19" };
const note = { id: "note", vaultId: "vault", path: "Projects/Plan.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-19T00:00:00Z" };
const page = (notes: object[], nextCursor: string | null = null) => ({ epochId: "e", notes, complete: nextCursor === null, nextCursor }) as never;
/** Ein kleiner Vault: Ordner und Einträge je Ebene, so wie der Server sie liefert. */
function serve(tree: Record<string, { folders?: string[]; notes?: object[] }>) {
  vi.mocked(api.folderChildren).mockImplementation(async (_vault, path) =>
    (tree[path]?.folders ?? []).map(child => ({ path: child, hasChildren: Object.keys(tree).some(key => key === child && (tree[key].folders?.length ?? 0) > 0) || !!tree[child] })));
  vi.mocked(api.listFolder).mockImplementation(async (_vault, folder) => page(tree[folder]?.notes ?? []));
}
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.permissions).mockResolvedValue(["READ", "CREATE"]);
  serve({ "": { folders: ["Projects"] }, Projects: { notes: [note] } });
});
afterEach(cleanup);
describe("notes workspace", () => {
  it("searches on the server and clearly explains empty search results", async () => {
    vi.mocked(api.searchNotes).mockResolvedValue({ notes: [], truncated: false });
    render(NotesWorkspace, { vault, onDirtyChange: vi.fn() });
    await fireEvent.click(await screen.findByRole("button", { name: "Ordner Projects" }));
    await screen.findByRole("button", { name: /Plan/ });
    await fireEvent.input(screen.getByRole("searchbox"), { target: { value: "missing" } });
    await screen.findByText("Keine passenden Notizen.");
    expect(api.searchNotes).toHaveBeenCalledWith("vault", "missing");
    expect(screen.queryByRole("button", { name: /Plan/ })).toBeNull();
  });
  it("shows the server's hits and says when there are more than shown", async () => {
    const hit = { ...note, id: "hit", path: "Studium/Grenzwerte.md" };
    vi.mocked(api.searchNotes).mockResolvedValue({ notes: [hit], truncated: true });
    render(NotesWorkspace, { vault, onDirtyChange: vi.fn() });
    await screen.findByRole("button", { name: "Ordner Projects" });
    await fireEvent.input(screen.getByRole("searchbox"), { target: { value: "grenz" } });
    await screen.findByRole("button", { name: /Grenzwerte/ });
    expect(screen.getByText(/Weitere Treffer/)).toBeTruthy();
  });
  it("loads only the top level at first, and a folder when it is opened", async () => {
    render(NotesWorkspace, { vault, onDirtyChange: vi.fn() });
    const folder = await screen.findByRole("button", { name: "Ordner Projects" });
    expect(folder.getAttribute("aria-expanded")).toBe("false");
    expect(api.listFolder).toHaveBeenCalledTimes(1);
    expect(api.listFolder).toHaveBeenCalledWith("vault", "", undefined);

    await fireEvent.click(folder);

    await screen.findByRole("button", { name: /Plan/ });
    expect(api.listFolder).toHaveBeenLastCalledWith("vault", "Projects", undefined);
    expect(api.listNotes).not.toHaveBeenCalled();
  });
  it("loads more of a long folder on request", async () => {
    vi.mocked(api.listFolder).mockImplementation(async (_vault, folder, cursor) => folder !== "" ? page([])
      : cursor ? page([{ ...note, id: "b", path: "B.md" }]) : page([{ ...note, id: "a", path: "A.md" }], "p:A.md"));
    vi.mocked(api.folderChildren).mockResolvedValue([]);
    render(NotesWorkspace, { vault, onDirtyChange: vi.fn() });

    await fireEvent.click(await screen.findByRole("button", { name: "Weitere laden" }));

    await screen.findByRole("button", { name: /^B/ });
    expect(api.listFolder).toHaveBeenLastCalledWith("vault", "", "p:A.md");
  });
  // Dateien (ADR 0009) stehen mit in der Liste - mit Endung und Art, geoeffnet in der Dateiansicht.
  it("lists files next to notes and opens them in the file view", async () => {
    const pdf = { id: "f1", vaultId: "vault", path: "Anhänge/Skript.pdf", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-23T00:00:00Z",
      kind: "FILE" as const, sha256: "ab", size: 1024, revision: 1 };
    serve({ "": { notes: [note, pdf] } });
    vi.mocked(api.fileBlob).mockResolvedValue(new Blob(["%PDF"], { type: "application/pdf" }));
    URL.createObjectURL = vi.fn(() => "blob:x");
    URL.revokeObjectURL = vi.fn();
    render(NotesWorkspace, { vault, onDirtyChange: vi.fn() });

    await screen.findByText(/1 Notiz, 1 Datei/);
    await fireEvent.click(await screen.findByRole("button", { name: /Skript\.pdf.*PDF/ }));

    await screen.findByRole("link", { name: "Herunterladen" });
  });
  it("shows similar notes and opens one with a click", async () => {
    const other = { id: "other", vaultId: "vault", path: "Biologie/Photosynthese.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-20T00:00:00Z" };
    serve({ "": { notes: [note, other] } });
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

describe("folder navigation", () => {
  it("opens and closes nested folders level by level, without downloading note content", async () => {
    serve({ "": { folders: ["Projects"] }, Projects: { folders: ["Projects/Research"], notes: [note] },
      "Projects/Research": { notes: [{ ...note, id: "nested", path: "Projects/Research/Outline.md" }] } });
    render(NotesWorkspace, { vault, onDirtyChange: vi.fn() });
    const folder = await screen.findByRole("button", { name: "Ordner Projects" });
    await fireEvent.click(folder);
    await fireEvent.click(await screen.findByRole("button", { name: "Ordner Projects/Research" }));
    await screen.findByRole("button", { name: /Outline/ });

    await fireEvent.click(folder);
    expect(screen.queryByRole("button", { name: /Outline/ })).toBeNull();
    await fireEvent.click(folder);
    await screen.findByRole("button", { name: /Outline/ });
    expect(vi.mocked(api.listFolder).mock.calls.filter(([, path]) => path === "Projects")).toHaveLength(1);
    expect(api.noteContent).not.toHaveBeenCalled();
  });
});
