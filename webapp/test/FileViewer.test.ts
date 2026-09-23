import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import FileViewer from "../src/lib/components/FileViewer.svelte";
import { api, type Note } from "../src/lib/api";
vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: {
  fileBlob: vi.fn(), renameNote: vi.fn(), deleteNote: vi.fn(),
} }));
const file = (path: string, size = 2048): Note => ({ id: "f1", vaultId: "vault", path, noteLevel: 1, createdBy: "Tom",
  createdAt: "2026-09-23T10:00:00Z", kind: "FILE", sha256: "ab", size, revision: 2 });

beforeEach(() => {
  vi.resetAllMocks();
  URL.createObjectURL = vi.fn(() => "blob:preview");
  URL.revokeObjectURL = vi.fn();
});
afterEach(cleanup);

describe("Dateiansicht", () => {
  it("shows images directly, loaded with the login", async () => {
    vi.mocked(api.fileBlob).mockResolvedValue(new Blob(["png"], { type: "image/png" }));
    render(FileViewer, { note: file("Bilder/Foto.png"), permissions: ["READ"], onChanged: vi.fn(), onDeleted: vi.fn() });

    const image = await screen.findByRole("img", { name: "Foto.png" });
    expect(image.getAttribute("src")).toBe("blob:preview");
    expect(api.fileBlob).toHaveBeenCalledWith("vault", "f1");
    expect(screen.getByText("2 KB · Bilder")).toBeTruthy();
  });

  it("shows PDFs in the browser's viewer", async () => {
    vi.mocked(api.fileBlob).mockResolvedValue(new Blob(["%PDF"], { type: "application/pdf" }));
    render(FileViewer, { note: file("Skript.pdf"), permissions: ["READ"], onChanged: vi.fn(), onDeleted: vi.fn() });

    const frame = await screen.findByTitle("Skript.pdf");
    expect(frame.getAttribute("src")).toBe("blob:preview");
  });

  it("offers a download for everything, and says when there is no preview", async () => {
    vi.mocked(api.fileBlob).mockResolvedValue(new Blob(["zip"], { type: "application/zip" }));
    render(FileViewer, { note: file("Archiv.zip"), permissions: ["READ"], onChanged: vi.fn(), onDeleted: vi.fn() });

    await screen.findByText(/Für diese Datei gibt es keine Vorschau/);
    const link = screen.getByRole("link", { name: "Herunterladen" });
    expect(link.getAttribute("download")).toBe("Archiv.zip");
    expect(link.getAttribute("href")).toBe("blob:preview");
  });

  // Grosse Dateien nicht ungefragt in den Speicher laden.
  it("loads large files only on request", async () => {
    vi.mocked(api.fileBlob).mockResolvedValue(new Blob(["x"], { type: "video/mp4" }));
    render(FileViewer, { note: file("Film.mp4", 80 * 1024 * 1024), permissions: ["READ"], onChanged: vi.fn(), onDeleted: vi.fn() });

    await fireEvent.click(await screen.findByRole("button", { name: "Datei laden (80 MB)" }));

    await waitFor(() => expect(api.fileBlob).toHaveBeenCalledTimes(1));
  });

  it("renames and deletes with the same rights as notes", async () => {
    vi.mocked(api.fileBlob).mockResolvedValue(new Blob(["png"], { type: "image/png" }));
    vi.mocked(api.renameNote).mockResolvedValue(file("Archiv/Foto.png"));
    vi.mocked(api.deleteNote).mockResolvedValue(undefined);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const onChanged = vi.fn();
    const onDeleted = vi.fn();
    render(FileViewer, { note: file("Bilder/Foto.png"), permissions: ["READ", "WRITE", "DELETE"], onChanged, onDeleted });

    await fireEvent.click(await screen.findByRole("button", { name: "Umbenennen / verschieben" }));
    await fireEvent.input(screen.getByLabelText("Neuer Pfad"), { target: { value: "Archiv/Foto.png" } });
    await fireEvent.click(screen.getByRole("button", { name: "Pfad speichern" }));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(api.renameNote).toHaveBeenCalledWith("vault", "f1", "Archiv/Foto.png");

    await fireEvent.click(screen.getByRole("button", { name: "Datei löschen" }));
    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
  });

  it("offers no changes without the rights for them", async () => {
    vi.mocked(api.fileBlob).mockResolvedValue(new Blob(["png"], { type: "image/png" }));
    render(FileViewer, { note: file("Foto.png"), permissions: ["READ"], onChanged: vi.fn(), onDeleted: vi.fn() });

    await screen.findByRole("img");
    expect(screen.queryByRole("button", { name: "Datei löschen" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Umbenennen / verschieben" })).toBeNull();
  });
});
