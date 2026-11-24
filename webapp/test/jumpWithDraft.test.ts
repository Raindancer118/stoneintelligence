import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as Y from "yjs";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/svelte";
import App from "../src/App.svelte";
import { getUser } from "../src/lib/auth";
import { api } from "../src/lib/api";
vi.mock("../src/lib/auth", () => ({ getUser: vi.fn(), completeLogin: vi.fn(), login: vi.fn(), logout: vi.fn(), preferredUsername: () => "Tom" }));

const a = { id: "a", vaultId: "vault", path: "Alpha.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-19T00:00:00Z" };
const b = { id: "b", vaultId: "vault", path: "Beta.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-19T00:00:00Z" };
function encoded(text: string) {
  const doc = new Y.Doc(); doc.getText("content").insert(0, text);
  return btoa(String.fromCharCode(...Y.encodeStateAsUpdate(doc)));
}
beforeEach(() => {
  vi.restoreAllMocks();
  vi.mocked(getUser).mockResolvedValue({ profile: { sub: "tom" } } as Awaited<ReturnType<typeof getUser>>);
  vi.spyOn(api, "listVaults").mockResolvedValue([{ id: "vault", name: "Team", createdAt: "" }]);
  vi.spyOn(api, "permissions").mockResolvedValue(["READ", "WRITE"]);
  vi.spyOn(api, "folderChildren").mockResolvedValue([]);
  vi.spyOn(api, "listFolder").mockResolvedValue({ epochId: "e", notes: [a, b], complete: true, nextCursor: null });
  vi.spyOn(api, "noteContent").mockResolvedValue({ revision: 1, updates: [encoded("text")] });
  vi.spyOn(api, "searchNotes").mockResolvedValue({ notes: [b], truncated: false });
});
afterEach(cleanup);

describe("quick jump with an unsaved draft", () => {
  it("asks once whether to discard, not once in the app and again in the notes view", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(App);
    await fireEvent.click(await screen.findByRole("button", { name: /Alpha/ }, { timeout: 8000 }));
    await fireEvent.click(await screen.findByRole("button", { name: "Bearbeiten" }));
    await fireEvent.input(await screen.findByRole("textbox", { name: "Markdown-Inhalt" }), { target: { value: "geändert" } });
    await fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    await fireEvent.input(await screen.findByRole("searchbox", { name: "Ziel suchen" }), { target: { value: "beta" } });
    await fireEvent.click(await within(screen.getByRole("dialog")).findByRole("button", { name: /Beta/ }, { timeout: 5000 }));
    await screen.findByRole("heading", { name: "Beta", level: 2 });
    expect(confirm).toHaveBeenCalledTimes(1);
  }, 20_000);
});
