import { fireEvent, render, screen } from "@testing-library/svelte";
import { beforeEach, describe, expect, it, vi } from "vitest";
import SetupChecklist from "../src/lib/components/SetupChecklist.svelte";
import { setupDismissed } from "../src/lib/setupSteps";

const steps = [
  { id: "note" as const, label: "Erste Notiz anlegen", hint: "", done: true },
  { id: "invite" as const, label: "Leute einladen", hint: "Unter Mitglieder", done: false },
];

describe("setup checklist", () => {
  beforeEach(() => localStorage.clear());

  it("counts what is done and leads to the next step", async () => {
    const onStep = vi.fn();
    render(SetupChecklist, { vaultId: "v", steps, onStep, onDismiss: vi.fn() });
    expect(screen.getByText("1 von 2 erledigt")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Leute einladen" }));
    expect(onStep).toHaveBeenCalledWith("invite");
    expect((screen.getByRole("button", { name: "Erste Notiz anlegen" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("can be hidden for good", async () => {
    const onDismiss = vi.fn();
    render(SetupChecklist, { vaultId: "v", steps, onStep: vi.fn(), onDismiss });
    await fireEvent.click(screen.getByRole("button", { name: "Ausblenden" }));
    expect(onDismiss).toHaveBeenCalled();
    expect(setupDismissed("v")).toBe(true);
  });
});
