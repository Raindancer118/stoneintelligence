import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import NoteEditor from "../src/lib/components/NoteEditor.svelte";
import { api, type CommentThreadView } from "../src/lib/api";
import { prepareUpdate } from "../src/lib/noteContent";
import { requestCommentFocus } from "../src/lib/commentFocus";

vi.mock("../src/lib/api", async (original) => ({ ...(await original<object>()), api: {
  noteContent: vi.fn(), noteHistory: vi.fn(), listComments: vi.fn(), listMembers: vi.fn(), startThread: vi.fn(),
} }));

const note = { id: "note", vaultId: "vault", path: "Notes/Plan.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-19T10:00:00Z" };
const updates = [prepareUpdate([], "# Plan\n\nWir starten im **Mai** mit dem Umbau.")];
const props = (over: object = {}) => ({ note, permissions: ["READ"], onChanged: vi.fn(), onDeleted: vi.fn(), onDirtyChange: vi.fn(), ...over });
const thread = (id: string, resolved = false): CommentThreadView => ({
  id, noteId: "note", anchor: { quote: "im Mai", prefix: "", suffix: "" }, resolved, resolvedByName: null, resolvedAt: null,
  createdAt: "2026-11-25T09:00:00Z",
  comments: [{ id: `${id}-c`, author: "ben", authorName: "Ben Berg", mine: false, body: "Passt das?", createdAt: "2026-11-25T09:00:00Z", editedAt: null }],
});

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.noteContent).mockResolvedValue({ revision: 1, updates });
  vi.mocked(api.listComments).mockResolvedValue([thread("a"), thread("b"), thread("c", true)]);
  vi.mocked(api.listMembers).mockResolvedValue([]);
});
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe("note editor comments", () => {
  it("shows the number of open threads on the Kommentare button, even for read-only people", async () => {
    render(NoteEditor, props());

    const button = await screen.findByRole("button", { name: "Kommentare, 2 offen" });
    expect(api.listComments).toHaveBeenCalledWith("vault", "note");
    await fireEvent.click(button);
    expect(screen.getByRole("complementary", { name: "Kommentare" })).toBeTruthy();
    expect(screen.getAllByText("Ben Berg").length).toBeGreaterThan(0);
  });

  it("offers no comments on encrypted notes", async () => {
    render(NoteEditor, props({ note: { ...note, noteLevel: 101 } }));
    await screen.findByText("Verschlüsselte Notiz");

    expect(screen.queryByRole("button", { name: /^Kommentare/ })).toBeNull();
    expect(api.listComments).not.toHaveBeenCalled();
  });

  it("asks the server again every 20 seconds", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    render(NoteEditor, props());
    await waitFor(() => expect(api.listComments).toHaveBeenCalledTimes(1));

    await vi.advanceTimersByTimeAsync(20_000);

    expect(api.listComments).toHaveBeenCalledTimes(2);
  });

  it("comments on a selection in reading mode and sends the quote with its surroundings", async () => {
    vi.mocked(api.startThread).mockResolvedValue(thread("new"));
    const { container } = render(NoteEditor, props());
    await screen.findByText(/mit dem Umbau/);
    const paragraph = container.querySelector(".markdown p")!;
    const range = document.createRange();
    range.setStart(paragraph.firstChild!, 4);
    range.setEnd(paragraph.firstChild!, 12);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(range);

    await fireEvent.mouseUp(container.querySelector(".reading")!);
    await fireEvent.click(await screen.findByRole("button", { name: "Kommentieren" }));
    const field = await screen.findByRole("combobox", { name: "Kommentar zur markierten Stelle" });
    await fireEvent.input(field, { target: { value: "Bitte prüfen" } });
    await fireEvent.click(screen.getAllByRole("button", { name: "Kommentieren" }).at(-1)!);

    await waitFor(() => expect(api.startThread).toHaveBeenCalledOnce());
    const [vault, noteId, body, anchor] = vi.mocked(api.startThread).mock.calls[0]!;
    expect([vault, noteId, body]).toEqual(["vault", "note", "Bitte prüfen"]);
    expect(anchor).toMatchObject({ quote: "starten", prefix: "Plan Wir ", suffix: expect.stringMatching(/^ im Mai mit dem Umbau/) });
  });

  it("opens the panel on the thread a notification pointed to", async () => {
    requestCommentFocus("note", "b");
    render(NoteEditor, props());

    await screen.findByRole("complementary", { name: "Kommentare" });
    await waitFor(() => expect(document.getElementById("thread-b")?.classList.contains("focused")).toBe(true));
  });
});
