import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/svelte";
import SharePanel from "../src/lib/components/SharePanel.svelte";
import { api, ApiError } from "../src/lib/api";

vi.mock("../src/lib/api", async (original) => ({
  ...(await original<typeof import("../src/lib/api")>()),
  api: { noteAccess: vi.fn(), folderAccess: vi.fn(), listGroups: vi.fn(), putNoteGrant: vi.fn(), putFolderGrant: vi.fn(), removeNoteGrant: vi.fn(), removeFolderGrant: vi.fn() },
}));

const vault = { id: "vault", name: "Team", createdAt: "2026-09-19" };
const target = { kind: "entry" as const, noteId: "n1", path: "Team/Plan.md" };
const folderGrant = { id: "g1", target: { kind: "folder" as const, path: "Team", noteId: null }, scopeType: "USER" as const, subject: "ben",
  groupName: null, permissions: [], inheritsVault: false };
const managerReport = {
  target: { kind: "entry" as const, path: "Team/Plan.md", noteId: "n1" },
  mine: { permissions: ["READ", "WRITE", "MANAGE"] as const, source: null },
  grants: [], inherited: [folderGrant],
  members: [
    { subject: "ben", groups: ["Lektorat"], permissions: [], source: folderGrant },
    { subject: "tom", groups: ["owners"], permissions: ["READ", "WRITE", "MANAGE"], source: null },
  ],
};

beforeEach(() => {
  vi.mocked(api.listGroups).mockResolvedValue([{ id: "grp", name: "Lektorat", memberSubjects: ["ben"], roleIds: [] }]);
  vi.mocked(api.noteAccess).mockResolvedValue(structuredClone(managerReport) as never);
});
afterEach(() => { cleanup(); vi.resetAllMocks(); });

describe("share panel", () => {
  it("shows who has access and where it comes from", async () => {
    render(SharePanel, { props: { vault, target, onClose: vi.fn() } });

    expect(await screen.findByLabelText("Rechte für ben")).toBeTruthy();
    expect(screen.getByText(/Kein Zugriff · Freigabe für Ordner „Team“ · Lektorat/)).toBeTruthy();
    expect(screen.getByText("Von weiter oben")).toBeTruthy();
  });

  it("lets a manager give someone edit rights on exactly this note", async () => {
    vi.mocked(api.putNoteGrant).mockResolvedValue(folderGrant as never);
    render(SharePanel, { props: { vault, target, onClose: vi.fn() } });

    await fireEvent.change(await screen.findByLabelText("Rechte für ben"), { target: { value: "edit" } });

    await screen.findByText("ben: Darf bearbeiten.");
    expect(api.putNoteGrant).toHaveBeenCalledWith("vault", "n1", { scopeType: "USER", subject: "ben", permissions: ["READ", "WRITE", "CREATE", "DELETE"] });
  });

  it("only shows their own rights to someone who does not manage the note", async () => {
    vi.mocked(api.noteAccess).mockResolvedValue({ ...managerReport, mine: { permissions: ["READ"], source: null }, members: [], inherited: [] } as never);
    render(SharePanel, { props: { vault, target, onClose: vi.fn() } });

    await screen.findByText("Freigaben ändern kann, wer diese Stelle verwaltet.");
    expect(screen.queryByText("Wer hat Zugriff")).toBeNull();
  });

  it("explains why the server refused", async () => {
    vi.mocked(api.putNoteGrant).mockRejectedValue(new ApiError(409));
    render(SharePanel, { props: { vault, target, onClose: vi.fn() } });

    await fireEvent.change(await screen.findByLabelText("Rechte für ben"), { target: { value: "none" } });

    expect((await screen.findByRole("alert")).textContent).toContain("niemand mehr");
  });
});
