import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import LinkingPanel from "../src/lib/components/LinkingPanel.svelte";
import { api } from "../src/lib/api";

vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: {
  linkingSettings: vi.fn(), updateLinking: vi.fn(), runLinking: vi.fn(),
} }));

const vault = { id: "vault", name: "Team", createdAt: "" };
const off = { enabled: false, linkHumanNotes: true, maxLinksPerNote: null, service: null, requestedBy: null, lastRunAt: null };

beforeEach(() => { vi.mocked(api.linkingSettings).mockResolvedValue({ ...off }); });
afterEach(() => { cleanup(); vi.resetAllMocks(); });

describe("linking panel", () => {
  it("lets a manager switch nightly linking on, keeping the other settings", async () => {
    vi.mocked(api.updateLinking).mockResolvedValue({ ...off, enabled: true, requestedBy: "tom" });
    render(LinkingPanel, { vault, permissions: ["READ", "WRITE", "MANAGE"] });

    await fireEvent.click(await screen.findByLabelText(/Jede Nacht um 2 Uhr verlinken/));

    await screen.findByText("Die Verlinkung läuft jetzt jede Nacht um 2 Uhr.");
    expect(api.updateLinking).toHaveBeenCalledWith("vault", { enabled: true, linkHumanNotes: true, maxLinksPerNote: null, service: null });
  });

  it("refuses a nonsense maximum before asking the server", async () => {
    render(LinkingPanel, { vault, permissions: ["READ", "WRITE", "MANAGE"] });

    await fireEvent.input(await screen.findByLabelText("Höchstens neue Links je Notiz und Lauf"), { target: { value: "0" } });
    await fireEvent.click(screen.getByRole("button", { name: "Speichern" }));

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(api.updateLinking).not.toHaveBeenCalled();
  });

  it("lets writers start a run, but only managers change the settings", async () => {
    vi.mocked(api.runLinking).mockResolvedValue({} as never);
    const started = vi.fn();
    render(LinkingPanel, { vault, permissions: ["READ", "WRITE"], onStarted: started });

    expect((await screen.findByLabelText(/Jede Nacht um 2 Uhr verlinken/) as HTMLInputElement).disabled).toBe(true);
    await fireEvent.click(screen.getByRole("button", { name: "Jetzt verlinken" }));

    await screen.findByText(/Die Verlinkung läuft/);
    expect(started).toHaveBeenCalled();
  });
});
