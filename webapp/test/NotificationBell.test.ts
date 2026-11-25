import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/svelte";
import NotificationBell from "../src/lib/components/NotificationBell.svelte";
import { api, type CommentNotification } from "../src/lib/api";

vi.mock("../src/lib/api", async (original) => ({ ...(await original<object>()), api: { notifications: vi.fn(), markNotificationsRead: vi.fn() } }));

const item = (id: string, read = false): CommentNotification => ({
  id, vaultId: "v", vaultName: "Team", noteId: "n", notePath: "Projekte/Plan.md", threadId: "t", commentId: "c",
  actorName: "Anna Adler", excerpt: "Schau mal @Tom", createdAt: "2026-11-25T09:00:00Z", read,
});

beforeEach(() => { vi.resetAllMocks(); vi.mocked(api.markNotificationsRead).mockResolvedValue(undefined); });
afterEach(cleanup);

describe("notification bell", () => {
  it("shows the unread count and lists mentions with note and excerpt", async () => {
    vi.mocked(api.notifications).mockResolvedValue({ items: [item("1"), item("2", true)], unread: 1 });
    render(NotificationBell, { onOpen: vi.fn() });

    const bell = await screen.findByRole("group", { hidden: true, name: "Hinweise" });
    await waitFor(() => expect(screen.getByLabelText("Hinweise, 1 ungelesen")).toBeTruthy());
    expect(bell.textContent).toContain("Anna Adler hat dich erwähnt");
    expect(bell.textContent).toContain("Team · Plan");
    expect(bell.textContent).toContain("Schau mal @Tom");
  });

  it("marks one as read and opens it", async () => {
    vi.mocked(api.notifications).mockResolvedValue({ items: [item("1")], unread: 1 });
    const onOpen = vi.fn();
    render(NotificationBell, { onOpen });
    await waitFor(() => expect(screen.getByLabelText("Hinweise, 1 ungelesen")).toBeTruthy());

    await fireEvent.click(screen.getByRole("button", { hidden: true, name: /Anna Adler hat dich erwähnt/ }));

    expect(api.markNotificationsRead).toHaveBeenCalledWith(["1"]);
    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ id: "1", noteId: "n", threadId: "t" }));
    await waitFor(() => expect(screen.getByLabelText("Hinweise", { selector: "summary" })).toBeTruthy());
  });

  it("marks everything read", async () => {
    vi.mocked(api.notifications).mockResolvedValue({ items: [item("1"), item("2")], unread: 2 });
    render(NotificationBell, { onOpen: vi.fn() });
    await waitFor(() => expect(screen.getByLabelText("Hinweise, 2 ungelesen")).toBeTruthy());

    await fireEvent.click(screen.getByRole("button", { hidden: true, name: "Alle als gelesen markieren" }));

    expect(api.markNotificationsRead).toHaveBeenCalledWith();
    await waitFor(() => expect(screen.getByLabelText("Hinweise", { selector: "summary" })).toBeTruthy());
  });

  it("stays quiet when the server cannot be reached", async () => {
    vi.mocked(api.notifications).mockRejectedValue(new Error("offline"));
    render(NotificationBell, { onOpen: vi.fn() });

    expect(await screen.findByText("Hinweise sind gerade nicht erreichbar.")).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
