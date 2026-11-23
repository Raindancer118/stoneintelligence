import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import QuickJump from "../src/lib/components/QuickJump.svelte";
import { api } from "../src/lib/api";
vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: { searchNotes: vi.fn() } }));
beforeEach(() => vi.resetAllMocks());
afterEach(cleanup);

describe("quick jump failures", () => {
  it("says the search failed instead of claiming there is no match", async () => {
    vi.mocked(api.searchNotes).mockRejectedValue(new Error("Zu viele Anfragen."));
    render(QuickJump, { vaultId: "vault", areas: [], onNote: vi.fn(), onArea: vi.fn(), onClose: vi.fn() });
    await fireEvent.input(screen.getByRole("searchbox", { name: "Ziel suchen" }), { target: { value: "abc" } });
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("Zu viele Anfragen.");
    expect(screen.queryByText("Kein passendes Ziel.")).toBeNull();
  });

  it("announces an empty result to screen readers", async () => {
    vi.mocked(api.searchNotes).mockResolvedValue({ notes: [], truncated: false });
    render(QuickJump, { vaultId: "vault", areas: [], onNote: vi.fn(), onArea: vi.fn(), onClose: vi.fn() });
    await fireEvent.input(screen.getByRole("searchbox", { name: "Ziel suchen" }), { target: { value: "abc" } });
    expect((await screen.findByText("Kein passendes Ziel.")).getAttribute("role")).toBe("status");
  });
});
