import type { Page } from "@playwright/test";
import * as Y from "yjs";

export const vaultId = "a0000000-0000-4000-8000-000000000001";
export const noteId = "b0000000-0000-4000-8000-000000000001";
const initial = "# Kundenportal\n\nHier sammeln wir Entscheidungen und nächste Schritte für unser gemeinsames Projekt.\n\n## Nächste Schritte\n\n- Inhalte mit dem Team abstimmen\n- Rückmeldungen aus dem Kundengespräch ergänzen\n- Den nächsten Entwurf gemeinsam prüfen\n\n## Entscheidungen\n\n| Bereich | Stand |\n| --- | --- |\n| Navigation | Freigegeben |\n| Inhalte | In Bearbeitung |\n\n> Gute Dokumentation macht Entscheidungen nachvollziehbar.";

export async function mockWorkspace(page: Page) {
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
  const vaults = [{ id: vaultId, name: "Team-Wissen", createdAt: "2026-09-19T09:00:00Z" }];
  let roles = [{ id: "reader", name: "Lesende", permissions: ["READ"] }];
  let groups = [{ id: "team", name: "Lerngruppe", memberSubjects: ["Tom", "Anna"], roleIds: ["reader"] }];
  let members = [{ subject: "Tom", permissions: ["READ", "WRITE", "CREATE", "DELETE", "MANAGE"], groups: [{ id: "team", name: "Lerngruppe" }] }, { subject: "Anna", permissions: ["READ"], groups: [{ id: "team", name: "Lerngruppe" }] }];
  let grants: Record<string, unknown>[] = [];
  let linking = { enabled: false, linkHumanNotes: false, mode: "LITERAL", service: "gemini", maxLinksPerNote: 5, requestedBy: "Tom", lastRunAt: null, aiConsent: false, aiConsentCount: 0 };
  const notes = [{ id: noteId, vaultId, path: "Projekte/Kundenportal.md", noteLevel: 1, createdBy: "Tom", createdAt: "2026-09-19T09:00:00Z" }];
  await page.route("**/api/v1/**", async route => {
    const request = route.request(); const path = new URL(request.url()).pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body });
    if (path.endsWith("/vaults")) {
      if (request.method() === "POST") { const vault = { id: "second-vault", name: request.postDataJSON().name, createdAt: "2026-09-27T10:00:00Z" }; vaults.push(vault); return json(vault); }
      return json(vaults);
    }
    if (/\/vaults\/[^/]+$/.test(path) && request.method() === "PATCH") { vaults[0]!.name = request.postDataJSON().name; return json(vaults[0]); }
    if (path.endsWith("/members") && request.method() === "GET") return json(members);
    if (/\/vaults\/[^/]+\/members\/[^/]+$/.test(path) && request.method() === "DELETE") { members = members.filter(m => m.subject !== decodeURIComponent(path.split("/").at(-1)!)); return json({}); }
    if (/\/roles$/.test(path)) {
      if (request.method() === "POST") { const role = { id: `role-${roles.length}`, ...request.postDataJSON() }; roles.push(role); return json(role); }
      return json(roles);
    }
    if (/\/roles\/[^/]+$/.test(path) && !path.includes("/groups/")) {
      const id = path.split("/").at(-1)!;
      if (request.method() === "DELETE") roles = roles.filter(r => r.id !== id);
      else { const r = roles.find(r => r.id === id)!; const data = request.postDataJSON(); if(data.name) r.name = data.name; if(data.permissions) r.permissions = data.permissions; }
      return json({});
    }
    if (path.endsWith("/groups")) {
      if (request.method() === "POST") { const group = { id: `group-${groups.length}`, name: request.postDataJSON().name, memberSubjects: [], roleIds: [] }; groups.push(group); return json(group); }
      return json(groups);
    }
    if (path.includes("/groups/")) {
      const parts = path.split("/groups/")[1]!.split("/"); const group = groups.find(g => g.id === parts[0])!;
      if (parts[1] === "members") { if (request.method() === "POST") group.memberSubjects.push(request.postDataJSON().subject); else group.memberSubjects = group.memberSubjects.filter(s => s !== parts[2]); }
      else if (parts[1] === "roles") { if(request.method() === "POST") group.roleIds.push(parts[2]!); else group.roleIds = group.roleIds.filter(r => r !== parts[2]); }
      else if(request.method() === "DELETE") groups = groups.filter(g => g.id !== parts[0]);
      else group.name = request.postDataJSON().name;
      return json({});
    }
    if (path.endsWith("/access/grants")) {
      if (request.method() === "PUT") { const grant = { id: "grant", ...request.postDataJSON(), target: { kind: "entry", noteId, path: notes[0]!.path }, inheritsVault: false }; grants = [grant]; return json(grant); }
      grants = []; return json({});
    }
    if (path.endsWith("/access")) return json({ target: { kind: "entry", noteId, path: notes[0]!.path }, mine: { permissions: ["READ", "WRITE", "MANAGE"], source: null }, members: members.map(m => ({ ...m, groups: m.groups.map(g => g.name), source: null })), grants,
      inherited: [{ id: "inherited", target: { kind: "folder", path: "Projekte", noteId: null }, scopeType: "GROUP", subject: "team", groupName: "Lerngruppe", permissions: ["READ"], inheritsVault: false }] });
    if (path.endsWith("/history")) return json({ events: [{ actor: "Tom", action: "NOTE_CREATED", path: notes[0]!.path, occurredAt: "2026-09-19T09:00:00Z", payload: {} }], activity: null });
    if (path.endsWith("/similar")) return json([]);
    if (path.endsWith("/linking")) { if (request.method() === "PUT") linking = { ...linking, ...request.postDataJSON() }; return json(linking); }
    if (path.endsWith("/linking/consent")) { linking.aiConsent = request.postDataJSON().consent; linking.aiConsentCount = linking.aiConsent ? 1 : 0; return json(linking); }
    if (path.endsWith("/linking/run")) return json({ id: "link-run" });
    if (path.endsWith("/capacity")) return json({ service: "gemini", exhausted: false, reportedAt: "2026-09-27T10:00:00Z", availableAgainAt: null, stale: false, providers: [{ provider: "google-gemini", exhausted: false, keys: 1, usableKeys: 1, observedAt: "2026-09-27T10:00:00Z", availableAgainAt: null, requests: { remaining: 820, limit: 1000, resetsAt: null }, tokens: null, credits: null }] });
    if (path.endsWith("/cancel")) { const job = aiJobs.find(j => j.id === path.split("/").at(-2))!; job.status = "CANCELLED"; return json(job); }
    if (/\/vaults\/[^/]+\/invitations\/[^/]+$/.test(path) && request.method() === "DELETE") { invitations.splice(0, invitations.length); return json({}); }
    if (path.endsWith("/accept")) return json({ vaultId, vaultName: "Team-Wissen" });
    if (path.endsWith("/permissions")) return json(["READ", "WRITE", "CREATE", "DELETE", "MANAGE"]);
    if (path.endsWith("/people")) return json([{ username: "anna", name: "Anna Arendt", maskedEmail: "a***@example.org", alreadyMember: false }]);
    if (path.endsWith("/members") && request.method() === "POST") return json({ status: "ADDED", displayName: "Anna Arendt" });
    if (path.endsWith("/invitations") && request.method() === "POST") {
      invitations.push({ id: "inv-1", email: request.postDataJSON().email, access: "EDIT", invitedBy: "Tom", createdAt: "2026-09-23T10:00:00Z", expiresAt: "2026-10-07T10:00:00Z" });
      return json({ status: "INVITED", displayName: request.postDataJSON().email });
    }
    if (path.endsWith("/invitations")) return json(invitations);

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
    if (path.endsWith("/notes/search")) {
      const q = (new URL(request.url()).searchParams.get("q") ?? "").toLocaleLowerCase();
      return json({ notes: notes.filter(n => n.path.toLocaleLowerCase().includes(q)), truncated: false });
    }
    if (path.endsWith("/folders/children")) {
      const parent = new URL(request.url()).searchParams.get("path") ?? "";
      const prefix = parent ? `${parent}/` : "";
      const children = new Set<string>();
      for (const n of notes) {
        if (!n.path.startsWith(prefix)) continue;
        const rest = n.path.slice(prefix.length);
        if (rest.includes("/")) children.add(prefix + rest.slice(0, rest.indexOf("/")));
      }
      return json([...children].sort().map(child => ({ path: child, hasChildren: notes.some(n => n.path.startsWith(`${child}/`) && n.path.slice(child.length + 1).includes("/")) })));
    }
    if (path.endsWith("/notes") && request.method() === "GET" && new URL(request.url()).searchParams.has("folder")) {
      const folder = new URL(request.url()).searchParams.get("folder") ?? "";
      return json({ epochId: "test-epoch", complete: true, nextCursor: null,
        notes: notes.filter(n => (n.path.includes("/") ? n.path.slice(0, n.path.lastIndexOf("/")) : "") === folder) });
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
}
