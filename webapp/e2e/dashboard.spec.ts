import { test, expect } from "@playwright/test";
import * as Y from "yjs";

const vaultId = "a0000000-0000-4000-8000-000000000001";
const noteId = "b0000000-0000-4000-8000-000000000001";
const initial = "# Kundenportal\n\nHier sammeln wir Entscheidungen und nächste Schritte für unser gemeinsames Projekt.\n\n## Nächste Schritte\n\n- Inhalte mit dem Team abstimmen\n- Rückmeldungen aus dem Kundengespräch ergänzen\n- Den nächsten Entwurf gemeinsam prüfen\n\n## Entscheidungen\n\n| Bereich | Stand |\n| --- | --- |\n| Navigation | Freigegeben |\n| Inhalte | In Bearbeitung |\n\n> Gute Dokumentation macht Entscheidungen nachvollziehbar.";

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem("oidc.user:https://identity.example.test:dashboard-test", JSON.stringify({
    access_token: "test-only-token", token_type: "Bearer", scope: "openid profile", expires_at: Math.floor(Date.now() / 1000) + 3600,
    profile: { sub: "test-user", preferred_username: "Tom", name: "Tom" },
  })));
  const doc = new Y.Doc(); doc.getText("content").insert(0, initial);
  const documents = new Map([[noteId, doc]]);
  const revisions = new Map([[noteId, 1]]);
  const invitations: unknown[] = [];
  const aiJobs: Record<string, unknown>[] = [
    { id: "done", service: "gemini", requestedBy: "Anna", fileName: "Skript Kapitel 4.pdf", size: 120000, level: 1, status: "SUCCEEDED",
      progress: "6 Notizen geschrieben", percent: 100, error: null, changeSetId: "cs-1", createdAt: "2026-09-23T09:00:00Z", finishedAt: "2026-09-23T09:02:00Z" },
    { id: "failed", service: "gemini", requestedBy: "Tom", fileName: "Scan Klausur.pdf", size: 900000, level: 1, status: "FAILED",
      progress: null, percent: null, error: "vor der KI geschützt — Dateiname enthält [noai]", changeSetId: null, createdAt: "2026-09-23T08:30:00Z", finishedAt: "2026-09-23T08:30:10Z" },
  ];
  const changeSets: Record<string, unknown>[] = [{ id: "cs-1", service: "gemini", agent: "ki:Gemini", requestedBy: "Anna",
    label: "Skript Kapitel 4.pdf", createdAt: "2026-09-23T09:00:00Z", revertedAt: null }];
  const notes = [{ id: noteId, vaultId, path: "Projekte/Kundenportal.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-19T09:00:00Z" }];
  await page.route("**/api/v1/**", async route => {
    const request = route.request(); const path = new URL(request.url()).pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });
    if (path.endsWith("/vaults")) return json([{ id: vaultId, name: "Team-Wissen", createdAt: "2026-09-19T09:00:00Z" }]);
    if (path.endsWith("/permissions")) return json(["READ", "WRITE", "CREATE", "DELETE", "MANAGE"]);
    if (path.endsWith("/people")) return json([{ username: "anna", name: "Anna Arendt", maskedEmail: "a***@example.org", alreadyMember: false }]);
    if (path.endsWith("/members") && request.method() === "POST") return json({ status: "ADDED", displayName: "Anna Arendt" });
    if (path.endsWith("/invitations") && request.method() === "POST") {
      invitations.push({ id: "inv-1", email: request.postDataJSON().email, access: "EDIT", invitedBy: "Tom", createdAt: "2026-09-23T10:00:00Z", expiresAt: "2026-10-07T10:00:00Z" });
      return json({ status: "INVITED", displayName: request.postDataJSON().email });
    }
    if (path.endsWith("/invitations")) return json(invitations);
    if (path.endsWith("/roles") || path.endsWith("/groups") || path.endsWith("/path-rules")) return json([]);
    if (path.startsWith("/api/v1/invitations/")) return json({
      state: "PENDING", vaultName: "Team-Wissen", invitedBy: "Tom", maskedEmail: "n***@example.org", access: "EDIT",
      expiresAt: "2026-10-07T10:00:00Z", enrollmentUrl: "https://portal.example/if/flow/stoneintelligence-invitation/?itoken=1",
    });
    if (path.endsWith("/content")) {
      const id = path.split("/").at(-2)!;
      const currentDoc = documents.get(id)!;
      const revision = revisions.get(id)!;
      if (request.method() === "POST") {
        const body = request.postDataJSON();
        if (body.expectedRevision !== revision) return json({}, 409);
        Y.applyUpdate(currentDoc, Buffer.from(body.update, "base64")); revisions.set(id, revision + 1);
        return json({ revision: revision + 1 });
      }
      return json({ revision, updates: revision ? [Buffer.from(Y.encodeStateAsUpdate(currentDoc)).toString("base64")] : [] });
    }
    if (path.endsWith("/notes")) {
      if (request.method() === "POST") { const note = { ...notes[0]!, id: "b0000000-0000-4000-8000-000000000002", path: request.postDataJSON().path }; notes.push(note); documents.set(note.id, new Y.Doc()); revisions.set(note.id, 0); return json(note); }
      return json({ epochId: "test-epoch", complete: true, nextCursor: null, notes });
    }
    if (path === "/api/v1/ai/services") return json([{ id: "gemini", name: "Gemini", levels: [1] }, { id: "lokal", name: "Lokales Modell", levels: [1, 2, 3] }]);
    if (path.endsWith("/ai/jobs") && request.method() === "POST") {
      const job = { id: `job-${aiJobs.length + 1}`, service: "lokal", requestedBy: "Tom", fileName: "Vorlesung Biologie.pdf", size: 48213, level: 2,
        status: "RUNNING", progress: "Seite 3 von 12", percent: 25, error: null, changeSetId: null, createdAt: "2026-09-23T10:05:00Z", finishedAt: null };
      aiJobs.unshift(job);
      return json([job]);
    }
    if (path.endsWith("/ai/jobs")) return json(aiJobs);
    if (path.endsWith("/revert")) { changeSets[0]!.revertedAt = "2026-09-23T10:10:00Z"; return json({ reverted: 3, conflicts: [{ path: "Wissen/Zellatmung.md", reason: "Seit der KI hat jemand weitergeschrieben" }] }); }
    if (/\/ai\/change-sets\/[^/]+$/.test(path)) return json({ changeSet: changeSets[0], changes: [
      { noteId: "n1", path: "Wissen/Photosynthese.md", kind: "CREATED", at: "2026-09-23T09:01:00Z" },
      { noteId: "n2", path: "Wissen/Zellatmung.md", kind: "UPDATED", at: "2026-09-23T09:01:10Z" },
      { noteId: "n3", path: "Quellen/Skript Kapitel 4.md", kind: "CREATED", at: "2026-09-23T09:01:20Z" }] });
    if (path.endsWith("/ai/change-sets")) return json(changeSets);
    if (path.endsWith("/audit")) return json([{ actor: "Tom", action: "note.created", payload: {}, occurredAt: "2026-09-19T09:00:00Z" }]);
    if (request.method() === "PATCH") { notes[0]!.path = request.postDataJSON().path; return json(notes[0]); }
    if (request.method() === "DELETE") { notes.splice(0, 1); return json({}); }
    if (/\/(roles|groups|path-rules)$/.test(path)) return json([]);
    return json({ error: "Unexpected test request" }, 500);
  });
});

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
