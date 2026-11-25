import { test, expect } from "@playwright/test";
import { mockWorkspace } from "./fixtures";
test.beforeEach(async ({ page }) => { await mockWorkspace(page); });

test("enforce a plugin policy, see the offending device and wipe it", async ({ page }) => {
  const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
  page.on("dialog", dialog => void dialog.accept());
  await page.goto("/");
  await page.getByRole("button", { name: "Einstellungen" }).click();
  await page.getByRole("button", { name: "Geräte und Plugins" }).click();

  const row = page.getByRole("row", { name: /Annas Mac/ });
  await expect(row.getByText("Entspricht der Richtlinie")).toBeVisible();

  await page.getByLabel("Richtlinie", { exact: true }).selectOption("ENFORCE");
  await page.getByLabel("Freigegebene Plugins").fill("calendar@1.5.10");
  await page.getByRole("button", { name: "Richtlinie speichern" }).click();
  await expect(page.getByText("Die Richtlinie wird durchgesetzt.")).toBeVisible();
  await expect(row.getByText("Verstößt gegen die Richtlinie: kanban 2.0.51")).toBeVisible();

  await row.getByRole("button", { name: "Daten auf dem Gerät löschen" }).click();
  await expect(row.getByText("Daten werden beim nächsten Verbinden gelöscht")).toBeVisible();
  await row.getByRole("button", { name: "Wieder zulassen" }).click();
  await expect(row.getByText("Verstößt gegen die Richtlinie: kanban 2.0.51")).toBeVisible();
  expect(errors).toEqual([]);
});
