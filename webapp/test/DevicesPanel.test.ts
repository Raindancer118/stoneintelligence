import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/svelte";
import DevicesPanel from "../src/lib/components/DevicesPanel.svelte";
import { api, type Device } from "../src/lib/api";

vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: {
  pluginPolicy: vi.fn(), setPluginPolicy: vi.fn(), listDevices: vi.fn(), revokeDevice: vi.fn(), wipeDevice: vi.fn(), reinstateDevice: vi.fn(),
} }));

const vault = { id: "vault", name: "Team" };
const device = (over: Partial<Device>): Device => ({
  deviceId: "dev-1", subject: "ben", person: "Ben Beispiel", name: "Bens Laptop", platform: "linux", obsidianVersion: "1.15.0", pluginVersion: "0.34.0",
  restrictedMode: false, plugins: [], firstSeen: "2026-11-24T08:00:00Z", lastSeen: "2026-11-25T08:00:00Z", status: "COMPLIANT", compliant: true,
  offending: [], revoked: false, wipePending: false, wipedAt: null, ...over,
});

beforeEach(() => {
  vi.mocked(api.pluginPolicy).mockResolvedValue({ mode: "OFF", allowed: [{ id: "calendar", version: "1.5.10" }], alwaysAllowed: "stoneintelligence" });
  vi.mocked(api.listDevices).mockResolvedValue([
    device({}),
    device({ deviceId: "dev-2", name: "Annas Mac", person: "Anna Arendt", status: "NON_COMPLIANT", compliant: false, offending: ["kanban 2.0.51"] }),
  ]);
  vi.spyOn(window, "confirm").mockReturnValue(true);
});
afterEach(() => { cleanup(); vi.resetAllMocks(); vi.restoreAllMocks(); });

describe("devices panel", () => {
  it("shows the policy and every device with person, status and the offending plugins", async () => {
    render(DevicesPanel, { vault });

    expect(await screen.findByLabelText("Freigegebene Plugins")).toHaveProperty("value", "calendar@1.5.10");
    const row = screen.getByText("Annas Mac").closest("tr")!;
    expect(within(row).getByText("Anna Arendt")).toBeTruthy();
    expect(within(row).getByText("Verstößt gegen die Richtlinie: kanban 2.0.51")).toBeTruthy();
    expect(within(screen.getByText("Bens Laptop").closest("tr")!).getByText("Entspricht der Richtlinie")).toBeTruthy();
  });

  it("saves the mode and the parsed list", async () => {
    vi.mocked(api.setPluginPolicy).mockResolvedValue({ mode: "ENFORCE", allowed: [], alwaysAllowed: "stoneintelligence" });
    render(DevicesPanel, { vault });

    await fireEvent.change(await screen.findByLabelText("Richtlinie"), { target: { value: "ENFORCE" } });
    await fireEvent.input(screen.getByLabelText("Freigegebene Plugins"), { target: { value: "calendar@1.5.10\ndataview" } });
    await fireEvent.click(screen.getByRole("button", { name: "Richtlinie speichern" }));

    await screen.findByText("Die Richtlinie wird durchgesetzt.");
    expect(api.setPluginPolicy).toHaveBeenCalledWith("vault", { mode: "ENFORCE", allowed: [{ id: "calendar", version: "1.5.10" }, { id: "dataview", version: null }] });
  });

  it("rejects an invalid line without calling the server", async () => {
    render(DevicesPanel, { vault });

    await fireEvent.input(await screen.findByLabelText("Freigegebene Plugins"), { target: { value: "../etc" } });
    await fireEvent.click(screen.getByRole("button", { name: "Richtlinie speichern" }));

    expect((await screen.findByRole("alert")).textContent).toContain("../etc");
    expect(api.setPluginPolicy).not.toHaveBeenCalled();
  });

  it("revokes and wipes a device after confirming, and offers to reinstate it", async () => {
    vi.mocked(api.revokeDevice).mockResolvedValue(undefined);
    vi.mocked(api.wipeDevice).mockResolvedValue(undefined);
    render(DevicesPanel, { vault });
    const row = (await screen.findByText("Bens Laptop")).closest("tr")!;

    await fireEvent.click(within(row).getByRole("button", { name: "Gerät sperren" }));
    await screen.findByText("„Bens Laptop“ ist gesperrt.");
    await fireEvent.click(within(screen.getByText("Bens Laptop").closest("tr")!).getByRole("button", { name: "Daten auf dem Gerät löschen" }));
    await screen.findByText(/löscht die Daten beim nächsten Verbinden/);

    expect(api.revokeDevice).toHaveBeenCalledWith("vault", "dev-1");
    expect(api.wipeDevice).toHaveBeenCalledWith("vault", "dev-1");
  });

  it("does nothing when the confirmation is declined", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(false);
    render(DevicesPanel, { vault });

    await fireEvent.click(within((await screen.findByText("Bens Laptop")).closest("tr")!).getByRole("button", { name: "Gerät sperren" }));

    expect(api.revokeDevice).not.toHaveBeenCalled();
  });

  it("offers to reinstate a revoked device", async () => {
    vi.mocked(api.listDevices).mockResolvedValue([device({ status: "REVOKED", revoked: true, compliant: false })]);
    vi.mocked(api.reinstateDevice).mockResolvedValue(undefined);
    render(DevicesPanel, { vault });

    await fireEvent.click(await screen.findByRole("button", { name: "Wieder zulassen" }));

    expect(api.reinstateDevice).toHaveBeenCalledWith("vault", "dev-1");
  });

  it("says so when no device has reported yet", async () => {
    vi.mocked(api.listDevices).mockResolvedValue([]);
    render(DevicesPanel, { vault });

    expect(await screen.findByText(/Noch kein Gerät hat sich gemeldet/)).toBeTruthy();
  });
});
