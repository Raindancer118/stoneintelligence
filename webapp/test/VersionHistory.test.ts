import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import VersionHistory from "../src/lib/components/VersionHistory.svelte";
import { api, ApiError } from "../src/lib/api";

vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: {
  noteVersions: vi.fn(), noteVersion: vi.fn(), restoreVersion: vi.fn(),
} }));

const note = { id: "n1", vaultId: "v1", path: "Team/plan.md" };
const list = {
  currentRevision: 9, total: 2,
  versions: [
    { revision: 9, firstRevision: 5, actor: "anna", startedAt: "2026-09-29T09:00:00Z", endedAt: "2026-09-29T09:10:00Z", updates: 5 },
    { revision: 4, firstRevision: 1, actor: "tom", startedAt: "2026-09-28T09:00:00Z", endedAt: "2026-09-28T09:10:00Z", updates: 4 },
  ],
};

beforeEach(() => {
  vi.mocked(api.noteVersions).mockResolvedValue(structuredClone(list));
  vi.mocked(api.noteVersion).mockResolvedValue({
    revision: 4, at: "2026-09-28T09:10:00Z", actor: "tom", text: "# Plan\nalt\n", current: "# Plan\nneu\n", currentRevision: 9,
  });
  vi.spyOn(window, "confirm").mockReturnValue(true);
});
afterEach(() => { cleanup(); vi.resetAllMocks(); });

describe("version history", () => {
  it("lists versions newest first and shows what an older one would change", async () => {
    render(VersionHistory, { note, writable: true, dirty: false, onRestored: vi.fn() });

    const items = await screen.findAllByRole("button", { name: /anna|tom/ });
    expect(items[0].textContent).toContain("aktueller Stand");
    await fireEvent.click(items[1]);

    expect(await screen.findByText("alt")).toBeTruthy();
    expect(screen.getByText("neu").className).toContain("removed");
    expect(api.noteVersion).toHaveBeenCalledWith("v1", "n1", 4);
  });

  it("restores an older version after asking, then reports back", async () => {
    vi.mocked(api.restoreVersion).mockResolvedValue({ revision: 10, changed: true });
    const restored = vi.fn();
    render(VersionHistory, { note, writable: true, dirty: false, onRestored: restored });

    await fireEvent.click((await screen.findAllByRole("button", { name: /tom/ }))[0]);
    await fireEvent.click(await screen.findByRole("button", { name: "Diese Version wiederherstellen" }));

    await screen.findByText("Version wiederhergestellt.");
    expect(api.restoreVersion).toHaveBeenCalledWith("v1", "n1", 4);
    expect(restored).toHaveBeenCalled();
  });

  it("offers no restore to readers, nor over unsaved changes", async () => {
    const { unmount } = render(VersionHistory, { note, writable: false, dirty: false, onRestored: vi.fn() });
    await fireEvent.click((await screen.findAllByRole("button", { name: /tom/ }))[0]);
    await screen.findByText("alt");
    expect(screen.queryByRole("button", { name: "Diese Version wiederherstellen" })).toBeNull();
    unmount();

    render(VersionHistory, { note, writable: true, dirty: true, onRestored: vi.fn() });
    await fireEvent.click((await screen.findAllByRole("button", { name: /tom/ }))[0]);
    expect(((await screen.findByRole("button", { name: "Diese Version wiederherstellen" })) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText(/Speichere oder verwirf zuerst/)).toBeTruthy();
  });

  it("explains a refusal in terms of versions", async () => {
    vi.mocked(api.restoreVersion).mockRejectedValue(new ApiError(403, "forbidden"));
    render(VersionHistory, { note, writable: true, dirty: false, onRestored: vi.fn() });

    await fireEvent.click((await screen.findAllByRole("button", { name: /tom/ }))[0]);
    await fireEvent.click(await screen.findByRole("button", { name: "Diese Version wiederherstellen" }));

    expect((await screen.findByRole("alert")).textContent).toContain("Schreibrecht");
  });
});
