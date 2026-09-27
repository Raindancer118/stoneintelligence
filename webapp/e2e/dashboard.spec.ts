import { test, expect } from "@playwright/test";
import { mockWorkspace } from "./fixtures";
test.beforeEach(async ({ page }) => { await mockWorkspace(page); });

async function openNote(page: import("@playwright/test").Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "Kundenportal Projekte" }).click();
  await expect(page.getByText("Hier sammeln wir Entscheidungen", { exact: false })).toBeVisible();
}

test("read, edit, save and reopen a note", async ({ page }, testInfo) => {
  const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
  await openNote(page);
  await page.screenshot({ path: testInfo.outputPath("dashboard-desktop.png"), fullPage: true });
  await page.getByRole("button", { name: "Bearbeiten", exact: true }).click();
  await page.getByRole("textbox", { name: "Markdown-Inhalt" }).fill("# Überarbeitet\n\nIm Browser gespeichert.");
  await page.getByRole("button", { name: "Speichern", exact: true }).click();
  await expect(page.getByText(/Gespeichert um/)).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "Kundenportal Projekte" }).click();
  await expect(page.getByText("Im Browser gespeichert.")).toBeVisible();
  expect(errors).toEqual([]);
});

test("preserves draft when the server rejects a stale revision", async ({ page }) => {
  await openNote(page);
  await page.route("**/content", route => route.request().method() === "POST" ? route.fulfill({ status: 409, json: {} }) : route.fallback());
  await page.getByRole("button", { name: "Bearbeiten", exact: true }).click();
  const editor = page.getByRole("textbox", { name: "Markdown-Inhalt" });
  await editor.fill("Mein Entwurf bleibt erhalten");
  await page.getByRole("button", { name: "Speichern", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Zwischenzeitlich geändert");
  await expect(editor).toHaveValue("Mein Entwurf bleibt erhalten");
  page.once("dialog", dialog => dialog.dismiss());
  await page.getByRole("button", { name: "Mitglieder & Rechte", exact: true }).click();
  await expect(editor).toHaveValue("Mein Entwurf bleibt erhalten");
});

test("mobile reading has no horizontal overflow and returns to the list", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openNote(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("dashboard-mobile.png"), fullPage: true });
  await page.getByRole("button", { name: "Zur Notizliste" }).click();
  await expect(page.getByRole("searchbox")).toBeVisible();
});

test("rename and confirmed deletion update the note list", async ({ page }) => {
  await openNote(page);
  await page.getByText("Details und Aktionen", { exact: true }).click();
  await page.getByRole("button", { name: "Umbenennen / verschieben" }).click();
  await page.getByLabel("Neuer Pfad", { exact: true }).fill("Archiv/Abschluss.md");
  await page.getByRole("button", { name: "Pfad speichern" }).click();
  await expect(page.getByRole("button", { name: "Abschluss Archiv" })).toBeVisible();
  page.once("dialog", dialog => dialog.accept());
  await page.getByRole("button", { name: "Notiz löschen" }).click();
  await expect(page.getByText("Noch keine Notizen vorhanden.")).toBeVisible();
});


test("create a note in a folder and save its first content", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Neue Notiz", exact: true }).click();
  await page.getByLabel("Titel oder Pfad der neuen Notiz").fill("Ideen/Neue Notiz");
  await page.getByRole("button", { name: "Notiz anlegen", exact: true }).click();
  await expect(page.getByText("Hier ist Platz für deine Gedanken.")).toBeVisible();
  await page.getByRole("button", { name: "Bearbeiten", exact: true }).click();
  await page.getByRole("textbox", { name: "Markdown-Inhalt" }).fill("Meine erste Notiz");
  await page.getByRole("button", { name: "Speichern", exact: true }).click();
  await expect(page.getByText(/Gespeichert um/)).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "Neue Notiz Ideen" }).click();
  await expect(page.getByText("Meine erste Notiz", { exact: true })).toBeVisible();
});

test("invite an existing account and an email address from the members page", async ({ page }) => {
  await openNote(page);
  await page.getByRole("button", { name: "Mitglieder & Rechte", exact: true }).click();
  const field = page.getByLabel("Name oder E-Mail-Adresse");
  await field.fill("an");
  await page.getByRole("button", { name: "Anna Arendt hinzufügen" }).click();
  await expect(page.getByRole("status")).toHaveText("Anna Arendt ist jetzt Mitglied.");
  await field.fill("neu@example.org");
  await page.getByRole("button", { name: "Einladung an neu@example.org senden" }).click();
  await expect(page.getByRole("status")).toHaveText("Einladung an neu@example.org verschickt.");
  await expect(page.getByRole("button", { name: "Einladung an neu@example.org zurückziehen" })).toBeVisible();
});

test("connect the selected vault to Obsidian from the vault", async ({ page }, testInfo) => {
  await openNote(page);
  await page.getByRole("button", { name: "In Obsidian", exact: true }).click();
  const connect = page.getByRole("link", { name: "Mit „Team-Wissen“ verbinden" }).first();
  await expect(connect).toHaveAttribute("href", /^obsidian:\/\/stoneintelligence-connect\?stoneVault=[0-9a-f-]{36}&name=Team-Wissen$/);
  await expect(page.getByRole("link", { name: "StoneIntelligence installieren" })).toHaveAttribute("href", "obsidian://brat?plugin=Raindancer118%2Fstoneintelligence");
  await page.screenshot({ path: testInfo.outputPath("vault-in-obsidian.png"), fullPage: true });
});

test("reads a document with AI and undoes the result", async ({ page }, testInfo) => {
  const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
  await page.goto("/");
  await page.getByRole("button", { name: "KI-Wissen" }).click();
  await expect(page.getByRole("heading", { name: "Wissen aus Dokumenten" })).toBeVisible();

  await page.getByLabel("KI-Dienst").selectOption("lokal");
  await page.getByLabel("Level der Dokumente").selectOption("2");
  await page.getByLabel("Dokumente", { exact: true }).setInputFiles({ name: "Vorlesung Biologie.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.7 test") });
  await page.getByRole("button", { name: "1 Dokument einlesen" }).click();
  await expect(page.getByText("Seite 3 von 12 · 25 %")).toBeVisible();

  await page.getByRole("button", { name: "Änderungen aus Skript Kapitel 4.pdf anzeigen" }).click();
  await expect(page.getByText("Wissen/Photosynthese.md")).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("ai-desktop.png"), fullPage: true });

  page.once("dialog", dialog => dialog.accept());
  await page.getByRole("button", { name: "Skript Kapitel 4.pdf rückgängig machen" }).click();
  await expect(page.getByText("3 Änderungen rückgängig gemacht")).toBeVisible();
  await expect(page.getByText("Wissen/Zellatmung.md – Seit der KI hat jemand weitergeschrieben")).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("ai-mobile.png"), fullPage: true });
  expect(errors).toEqual([]);
});
