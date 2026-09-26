import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import NoteEditor from "../src/lib/components/NoteEditor.svelte";
import { api, ApiError } from "../src/lib/api";
import { prepareUpdate, decodeContent } from "../src/lib/noteContent";
vi.mock("../src/lib/api", async (original) => ({ ...(await original<object>()), api: {
  noteContent: vi.fn(), saveContent: vi.fn(), noteHistory: vi.fn(), renameNote: vi.fn(), deleteNote: vi.fn(),
} }));
const note = { id: "note", vaultId: "vault", path: "Notes/Welcome.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-19T10:00:00Z" };
const updates = [prepareUpdate([], "# Welcome\nOriginal text")];
const props = () => ({ note, permissions: ["READ", "WRITE", "DELETE"], onChanged: vi.fn(), onDeleted: vi.fn(), onDirtyChange: vi.fn() });
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.noteContent).mockResolvedValue({ revision: 1, updates });
  vi.mocked(api.saveContent).mockResolvedValue({ revision: 2 });
});
afterEach(cleanup);
describe("note editor", () => {
  it("loads real content and saves an interoperable delta using the loaded revision", async () => {
    render(NoteEditor, props());
    await screen.findByText("Original text");
    await fireEvent.click(screen.getByRole("button", { name: "Bearbeiten", exact: true }));
    await fireEvent.input(screen.getByRole("textbox", { name: "Markdown-Inhalt" }), { target: { value: "Changed text" } });
    await fireEvent.click(screen.getByRole("button", { name: "Speichern", exact: true }));
    await waitFor(() => expect(api.saveContent).toHaveBeenCalledOnce());
    const args = vi.mocked(api.saveContent).mock.calls[0]!;
    expect(args.slice(0, 3)).toEqual(["vault", "note", 1]);
    const doc = decodeContent([...updates, args[3]]);
    expect(doc.getText("content").toString()).toBe("Changed text"); doc.destroy();
    await screen.findByText(/Gespeichert/);
  });
  it("preserves the user's draft after a revision conflict", async () => {
    vi.mocked(api.saveContent).mockRejectedValue(new ApiError(409));
    render(NoteEditor, props());
    await screen.findByText("Original text");
    await fireEvent.click(screen.getByRole("button", { name: "Bearbeiten", exact: true }));
    const editor = screen.getByRole("textbox", { name: "Markdown-Inhalt" }) as HTMLTextAreaElement;
    await fireEvent.input(editor, { target: { value: "My unsaved work" } });
    await fireEvent.click(screen.getByRole("button", { name: "Speichern", exact: true }));
    await screen.findByText(/Zwischenzeitlich geändert/);
    expect(editor.value).toBe("My unsaved work");
  });
  it("offers no mutation controls to a read-only customer", async () => {
    render(NoteEditor, { ...props(), permissions: ["READ"] });
    await screen.findByText("Original text");
    expect(screen.queryByRole("button", { name: "Bearbeiten", exact: true })).toBeNull();
    expect(screen.queryByRole("button", { name: "Notiz löschen" })).toBeNull();
  });
  it("shows who created, last edited and last opened the note, and what happened", async () => {
    vi.mocked(api.noteHistory).mockResolvedValue({
      activity: { createdBy: "tom", createdAt: "2026-09-19T10:00:00Z", lastEditedBy: "anna", lastEditedAt: "2026-09-26T09:00:00Z",
        lastOpenedBy: "ben", lastOpenedAt: "2026-09-26T09:30:00Z" },
      events: [{ actor: "tom", action: "note.created", payload: { path: "Notes/Welcome.md" }, occurredAt: "2026-09-19T10:00:00Z",
        noteId: "note", path: "Notes/Welcome.md", paths: ["Notes/Welcome.md"] }],
    });
    render(NoteEditor, props());
    await screen.findByText("Original text");

    await fireEvent.click(screen.getByRole("button", { name: "Änderungsverlauf" }));

    expect(await screen.findByText(/Zuletzt bearbeitet von anna/)).toBeTruthy();
    expect(screen.getByText(/Zuletzt geöffnet von ben/)).toBeTruthy();
    expect(screen.getByText("tom hat „Welcome“ angelegt")).toBeTruthy();
  });
});
