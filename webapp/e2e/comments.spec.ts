import { test, expect } from "@playwright/test";
import { mockWorkspace } from "./fixtures";
test.beforeEach(async ({ page }) => { await mockWorkspace(page); });

async function openNote(page: import("@playwright/test").Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "Ordner Projekte" }).click();
  await page.getByRole("button", { name: "Kundenportal Projekte" }).click();
  await expect(page.getByText("Hier sammeln wir Entscheidungen", { exact: false })).toBeVisible();
}

test("comment on a selected passage, mention a member and resolve the thread", async ({ page }, testInfo) => {
  const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
  await openNote(page);
  await expect(page.getByRole("button", { name: "Kommentare, 1 offen" })).toBeVisible();

  await page.getByText("Inhalte mit dem Team abstimmen").click({ clickCount: 3 });
  await page.getByRole("button", { name: "Kommentieren" }).click();

  const panel = page.getByRole("complementary", { name: "Kommentare" });
  await expect(panel.getByText("Zu dieser Stelle")).toBeVisible();
  const field = panel.getByRole("combobox", { name: "Kommentar zur markierten Stelle" });
  await field.pressSequentially("Bitte kurz prüfen, @An");
  await page.getByRole("option", { name: "Anna" }).click();
  await field.pressSequentially("danke");
  await panel.getByRole("button", { name: "Kommentieren" }).click();

  const thread = panel.getByRole("article", { name: "Diskussion von Tom" });
  await expect(thread.getByText("„Inhalte mit dem Team abstimmen“")).toBeVisible();
  await expect(thread.locator(".mention")).toHaveText("@Anna");
  await expect(page.getByRole("button", { name: "Kommentare, 2 offen" })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("comments-desktop.png"), fullPage: true });

  await thread.getByRole("button", { name: "Als gelöst markieren" }).click();
  await expect(page.getByRole("button", { name: "Kommentare, 1 offen" })).toBeVisible();
  await expect(thread).toBeHidden();
  await panel.getByRole("checkbox", { name: /Gelöste zeigen/ }).check();
  await expect(panel.getByText(/Gelöst von Tom/)).toBeVisible();
  expect(errors).toEqual([]);
});

test("reply to a thread", async ({ page }) => {
  await openNote(page);
  await page.getByRole("button", { name: "Kommentare, 1 offen" }).click();
  const thread = page.getByRole("article", { name: "Diskussion von Anna" });

  await thread.getByRole("button", { name: "Antworten" }).click();
  await thread.getByRole("combobox", { name: "Antwort" }).fill("Mache ich heute.");
  await thread.getByRole("button", { name: "Antworten" }).click();

  await expect(thread.getByText("Mache ich heute.")).toBeVisible();
});

test("the bell lists the mention and opens its thread", async ({ page }) => {
  await page.goto("/");
  const bell = page.getByRole("group", { name: "Hinweise" });
  await page.getByLabel("Hinweise, 1 ungelesen").click();
  await expect(bell.getByText("Anna hat dich erwähnt")).toBeVisible();
  await expect(bell.getByText("Kannst du das prüfen, @Tom?")).toBeVisible();

  await bell.getByRole("button", { name: /Anna hat dich erwähnt/ }).click();

  const panel = page.getByRole("complementary", { name: "Kommentare" });
  await expect(panel.getByRole("article", { name: "Diskussion von Anna" })).toBeVisible();
  await expect(panel.locator(".mention")).toHaveText("@Tom");
  await expect(page.locator("summary.bell-button")).toHaveAttribute("aria-label", "Hinweise");
});

test("a refused mention keeps the text and explains why", async ({ page }) => {
  await openNote(page);
  await page.route("**/notes/*/comments", route => route.request().method() === "POST"
    ? route.fulfill({ status: 422, json: { detail: "Anna darf diese Notiz nicht lesen und lässt sich deshalb nicht erwähnen." } })
    : route.fallback());
  await page.getByRole("button", { name: "Kommentare, 1 offen" }).click();
  const panel = page.getByRole("complementary", { name: "Kommentare" });
  const field = panel.getByRole("combobox", { name: "Kommentar zur Notiz" });

  await field.fill("Hallo @[Anna](Anna)");
  await panel.getByRole("button", { name: "Kommentieren" }).click();

  await expect(panel.getByRole("alert")).toContainText("Anna darf diese Notiz nicht lesen");
  await expect(field).toHaveValue("Hallo @[Anna](Anna)");
});
