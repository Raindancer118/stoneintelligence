import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import ObsidianSetup from "../src/lib/components/ObsidianSetup.svelte";
afterEach(cleanup);

const href = (name: string | RegExp) => screen.getByRole("link", { name }).getAttribute("href");

describe("Obsidian einrichten", () => {
  it("links every step straight into Obsidian", () => {
    render(ObsidianSetup, { vaults: [], signedIn: false, onLogin: vi.fn() });

    expect(href("Obsidian herunterladen")).toBe("https://obsidian.md/download");
    expect(href("BRAT in Obsidian öffnen")).toBe("obsidian://show-plugin?id=obsidian42-brat");
    expect(href("StoneIntelligence installieren")).toBe("obsidian://brat?plugin=Raindancer118%2Fstoneintelligence");
  });

  it("offers one connect link per vault, with id and name encoded", () => {
    render(ObsidianSetup, {
      vaults: [{ id: "2f719285-2483-4e59-89e0-334af2813a70", name: "Team & Co", createdAt: "" }], signedIn: true, onLogin: vi.fn(),
    });

    expect(href("Mit „Team & Co“ verbinden")).toBe(
      "obsidian://stoneintelligence-connect?stoneVault=2f719285-2483-4e59-89e0-334af2813a70&name=Team%20%26%20Co");
  });

  it("asks signed-out visitors to sign in for their personal connect link", async () => {
    const onLogin = vi.fn();
    render(ObsidianSetup, { vaults: [], signedIn: false, onLogin });

    await fireEvent.click(screen.getByRole("button", { name: "Anmelden, um deinen Vault zu verbinden" }));

    expect(onLogin).toHaveBeenCalled();
  });

  it("explains the manual way when there is no vault yet", () => {
    render(ObsidianSetup, { vaults: [], signedIn: true, onLogin: vi.fn() });

    expect(screen.getByText(/Du hast noch keinen Vault/)).toBeTruthy();
  });

  it("puts a direct connect button first when set up for one vault", () => {
    render(ObsidianSetup, { vaults: [{ id: "2f719285-2483-4e59-89e0-334af2813a70", name: "Team", createdAt: "" }], signedIn: true,
      onLogin: vi.fn(), scopedVault: true });

    expect(screen.getByRole("heading", { name: "„Team“ in Obsidian öffnen" })).toBeTruthy();
    expect(screen.getAllByRole("link", { name: "Mit „Team“ verbinden" })).toHaveLength(2);
  });
});

