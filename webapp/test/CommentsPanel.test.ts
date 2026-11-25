import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import CommentsPanel from "../src/lib/components/CommentsPanel.svelte";
import { api, ApiError, type CommentThreadView } from "../src/lib/api";

vi.mock("../src/lib/api", async (original) => ({ ...(await original<object>()), api: {
  listMembers: vi.fn(), startThread: vi.fn(), replyToThread: vi.fn(), editComment: vi.fn(), deleteComment: vi.fn(),
  resolveThread: vi.fn(), reopenThread: vi.fn(),
} }));

const note = { id: "note", vaultId: "vault", path: "Notes/Plan.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-19T10:00:00Z" };
const source = "# Plan\n\nWir starten im Mai mit dem Umbau.";
const thread = (id: string, over: Partial<CommentThreadView> = {}): CommentThreadView => ({
  id, noteId: "note", anchor: null, resolved: false, resolvedByName: null, resolvedAt: null, createdAt: "2026-11-25T09:00:00Z",
  comments: [{ id: `${id}-c1`, author: "ben", authorName: "Ben Berg", mine: false, body: "Passt das?", createdAt: "2026-11-25T09:00:00Z", editedAt: null }], ...over,
});
const props = (threads: CommentThreadView[] | null, extra: object = {}) => ({
  note, threads, source, pending: null, canManage: false, onClose: vi.fn(), onClearPending: vi.fn(), ...extra,
});

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.listMembers).mockResolvedValue([
    { subject: "anna", displayName: "Anna Adler", groups: [], permissions: ["READ"] },
    { subject: "ben", displayName: "Ben Berg", groups: [], permissions: ["READ"] },
  ]);
});
afterEach(cleanup);

describe("comments panel", () => {
  it("shows a skeleton while loading and an empty hint afterwards", async () => {
    const { unmount } = render(CommentsPanel, props(null));
    expect(screen.getByRole("status").textContent).toContain("Kommentare werden geladen…");
    unmount();
    render(CommentsPanel, props([]));
    expect(screen.getByText("Noch keine Kommentare.")).toBeTruthy();
  });

  it("shows the quoted passage, flags a vanished one and renders mentions as plain styled text", async () => {
    const found = thread("a", { anchor: { quote: "im Mai", prefix: "", suffix: "" } });
    const gone = thread("b", { anchor: { quote: "gibt es nicht mehr", prefix: "", suffix: "" }, comments: [
      { id: "b-c1", author: "ben", authorName: "Ben Berg", mine: false, body: "Hallo @[Anna Adler](anna) <b>fett</b>", createdAt: "2026-11-25T09:00:00Z", editedAt: null }] });
    const { container } = render(CommentsPanel, props([found, gone]));

    expect(screen.getAllByText(/„im Mai“/)).toHaveLength(1);
    expect(screen.getAllByText("Stelle geändert")).toHaveLength(1);
    expect(container.querySelector(".mention")?.textContent).toBe("@Anna Adler");
    expect(container.querySelector(".comment-body b")).toBeNull();
    expect(container.textContent).toContain("<b>fett</b>");
  });

  it("offers edit and delete only for own comments, and delete for managers", () => {
    const mine = thread("m", { comments: [{ id: "m-c1", author: "tom", authorName: "Tom", mine: true, body: "meins", createdAt: "2026-11-25T09:00:00Z", editedAt: "2026-11-25T10:00:00Z" }] });
    const { unmount } = render(CommentsPanel, props([mine, thread("o")]));
    expect(screen.getAllByRole("button", { name: "Bearbeiten" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "Löschen" })).toHaveLength(1);
    expect(screen.getByText("bearbeitet")).toBeTruthy();
    unmount();
    render(CommentsPanel, props([thread("o")], { canManage: true }));
    expect(screen.queryByRole("button", { name: "Bearbeiten" })).toBeNull();
    expect(screen.getByRole("button", { name: "Löschen" })).toBeTruthy();
  });

  it("suggests members after @, inserts the mention and sends the token with the quoted passage", async () => {
    vi.mocked(api.startThread).mockResolvedValue(thread("new"));
    const pending = { quote: "im Mai", prefix: "Wir starten ", suffix: " mit" };
    render(CommentsPanel, props([], { pending }));
    await waitFor(() => expect(api.listMembers).toHaveBeenCalled());
    const field = screen.getByRole("combobox", { name: "Kommentar zur markierten Stelle" }) as HTMLTextAreaElement;

    await fireEvent.input(field, { target: { value: "Hallo @be", selectionStart: 9 } });
    field.setSelectionRange(9, 9);
    await fireEvent.keyUp(field);
    const option = await screen.findByRole("option", { name: "Ben Berg" });
    await fireEvent.mouseDown(within(option).getByRole("button"));
    await fireEvent.click(screen.getByRole("button", { name: "Kommentieren" }));

    await waitFor(() => expect(api.startThread).toHaveBeenCalledWith("vault", "note", "Hallo @[Ben Berg](ben)", pending));
  });

  it("keeps the text and shows the reason when a mention is refused", async () => {
    vi.mocked(api.replyToThread).mockRejectedValue(new ApiError(422, "Ben Berg darf diese Notiz nicht lesen und lässt sich deshalb nicht erwähnen."));
    render(CommentsPanel, props([thread("a")]));
    await fireEvent.click(screen.getByRole("button", { name: "Antworten" }));
    const field = screen.getByRole("combobox", { name: "Antwort" }) as HTMLTextAreaElement;
    await fireEvent.input(field, { target: { value: "Hi @[Ben Berg](ben)" } });
    await fireEvent.click(screen.getByRole("button", { name: "Antworten" }));

    expect((await screen.findByRole("alert")).textContent).toContain("darf diese Notiz nicht lesen");
    expect(field.value).toBe("Hi @[Ben Berg](ben)");
  });

  it("resolves and reopens a thread, hiding resolved ones until asked", async () => {
    vi.mocked(api.resolveThread).mockResolvedValue(thread("a", { resolved: true, resolvedByName: "Tom", resolvedAt: "2026-11-25T11:00:00Z" }));
    vi.mocked(api.reopenThread).mockResolvedValue(thread("a"));
    render(CommentsPanel, props([thread("a")]));

    await fireEvent.click(screen.getByRole("button", { name: "Als gelöst markieren" }));
    expect(await screen.findByText("Alle Diskussionen sind gelöst.")).toBeTruthy();

    await fireEvent.click(screen.getByRole("checkbox", { name: /Gelöste zeigen/ }));
    expect(screen.getByText(/Gelöst von Tom/)).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Wieder öffnen" }));
    await waitFor(() => expect(screen.queryByText(/Gelöst von Tom/)).toBeNull());
  });

  it("deletes after confirmation and drops an emptied thread", async () => {
    vi.mocked(api.deleteComment).mockResolvedValue(undefined);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const mine = thread("m", { comments: [{ id: "m-c1", author: "tom", authorName: "Tom", mine: true, body: "weg damit", createdAt: "2026-11-25T09:00:00Z", editedAt: null }] });
    render(CommentsPanel, props([mine]));

    await fireEvent.click(screen.getByRole("button", { name: "Löschen" }));

    await waitFor(() => expect(screen.getByText("Noch keine Kommentare.")).toBeTruthy());
    expect(api.deleteComment).toHaveBeenCalledWith("vault", "m-c1");
  });
});
