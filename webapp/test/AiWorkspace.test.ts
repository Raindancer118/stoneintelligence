import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import AiWorkspace from "../src/lib/components/AiWorkspace.svelte";
import { api, type AiJob } from "../src/lib/api";
vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: {
  permissions: vi.fn(), aiServices: vi.fn(), listAiJobs: vi.fn(), uploadAiDocument: vi.fn(), cancelAiJob: vi.fn(),
  listChangeSets: vi.fn(), changeSet: vi.fn(), revertChangeSet: vi.fn(),
} }));
const vault = { id: "vault", name: "Studium", createdAt: "" };
const services = [
  { id: "gemini", name: "Gemini", levels: [1] },
  { id: "lokal", name: "Lokales Modell", levels: [1, 2, 3] },
];
function job(overrides: Partial<AiJob>): AiJob {
  return { id: "j1", service: "gemini", requestedBy: "tom", fileName: "Vorlesung.pdf", size: 1000, level: 1, status: "PENDING",
    progress: null, percent: null, error: null, changeSetId: null, createdAt: "2026-09-23T10:00:00Z", finishedAt: null, ...overrides };
}
const pdf = (name = "Vorlesung.pdf", size = 1000) => new File([new Uint8Array(size)], name, { type: "application/pdf" });

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.permissions).mockResolvedValue(["READ", "WRITE", "DELETE", "CREATE"]);
  vi.mocked(api.aiServices).mockResolvedValue(services);
  vi.mocked(api.listAiJobs).mockResolvedValue([]);
  vi.mocked(api.listChangeSets).mockResolvedValue([]);
});
afterEach(cleanup);

async function choose(files: File[]) {
  await fireEvent.change(screen.getByLabelText("Dokumente"), { target: { files } });
}

describe("KI-Bereich", () => {
  it("explains that no AI is set up when the server has no service", async () => {
    vi.mocked(api.aiServices).mockResolvedValue([]);
    render(AiWorkspace, { vault });

    await screen.findByText(/Auf diesem Server ist noch keine KI eingerichtet/);
    expect(screen.queryByLabelText("Dokumente")).toBeNull();
  });

  // Toms Vorgabe: je Dienst einstellbar, welche Levels er verarbeiten darf - die Auswahl zeigt nur, was geht.
  it("offers only the levels the chosen service may process", async () => {
    render(AiWorkspace, { vault });
    const levels = () => within(screen.getByLabelText("Level der Dokumente")).getAllByRole("option").map(o => o.textContent);

    await screen.findByLabelText("KI-Dienst");
    expect(levels()).toEqual(["Level 1"]);
    await fireEvent.change(screen.getByLabelText("KI-Dienst"), { target: { value: "lokal" } });
    expect(levels()).toEqual(["Level 1", "Level 2", "Level 3"]);
  });

  // DSGVO: vor dem Hochladen steht da, wohin der Inhalt geht.
  it("says which service will receive the content", async () => {
    render(AiWorkspace, { vault });

    await screen.findByText(/Der Text der Dokumente wird an „Gemini“ übertragen/);
  });

  it("uploads each chosen document for the chosen service and level, then shows it waiting", async () => {
    vi.mocked(api.uploadAiDocument).mockResolvedValue([job({})]);
    render(AiWorkspace, { vault });
    await screen.findByLabelText("KI-Dienst");
    await fireEvent.change(screen.getByLabelText("KI-Dienst"), { target: { value: "lokal" } });
    await fireEvent.change(screen.getByLabelText("Level der Dokumente"), { target: { value: "2" } });

    await choose([pdf("a.pdf"), pdf("b.pdf")]);
    vi.mocked(api.listAiJobs).mockResolvedValue([job({ fileName: "a.pdf" }), job({ id: "j2", fileName: "b.pdf" })]);
    await fireEvent.click(screen.getByRole("button", { name: "2 Dokumente einlesen" }));

    await waitFor(() => expect(api.uploadAiDocument).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.uploadAiDocument).mock.calls.map(([v, s, l, f]) => [v, s, l, (f as File).name]))
      .toEqual([["vault", "lokal", 2, "a.pdf"], ["vault", "lokal", 2, "b.pdf"]]);
    await screen.findByText("b.pdf");
    expect(screen.getAllByText("Wartet").length).toBe(2);
  });

  it("refuses files the pipeline cannot read or that are too large, before uploading", async () => {
    render(AiWorkspace, { vault });
    await screen.findByLabelText("Dokumente");

    await choose([new File(["x"], "bild.png", { type: "image/png" }), pdf("riesig.pdf", 21 * 1024 * 1024)]);

    await screen.findByText(/bild\.png: nur PDF-, Markdown- und Textdateien/);
    await screen.findByText(/riesig\.pdf ist größer als 20 MB/);
    expect(screen.queryByRole("button", { name: /einlesen/ })).toBeNull();
    expect(api.uploadAiDocument).not.toHaveBeenCalled();
  });

  it("shows progress and errors, and cancels a waiting document", async () => {
    vi.mocked(api.listAiJobs).mockResolvedValue([
      job({ id: "run", fileName: "läuft.pdf", status: "RUNNING", percent: 40, progress: "Seite 2 von 5" }),
      job({ id: "bad", fileName: "kaputt.pdf", status: "FAILED", error: "PDF ist verschlüsselt" }),
      job({ id: "wait", fileName: "wartet.pdf" }),
    ]);
    vi.mocked(api.cancelAiJob).mockResolvedValue(job({ id: "wait", status: "CANCELLED" }));
    render(AiWorkspace, { vault });

    await screen.findByText("Seite 2 von 5 · 40 %");
    expect(screen.getByText("PDF ist verschlüsselt")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "wartet.pdf abbrechen" }));

    await waitFor(() => expect(api.cancelAiJob).toHaveBeenCalledWith("vault", "wait"));
  });

  it("undoes an AI change after asking, and reports what it had to leave alone", async () => {
    vi.mocked(api.listChangeSets).mockResolvedValue([{ id: "cs", service: "gemini", agent: "ki:Gemini", requestedBy: "tom",
      label: "Vorlesung.pdf", createdAt: "2026-09-23T10:00:00Z", revertedAt: null }]);
    vi.mocked(api.changeSet).mockResolvedValue({ changeSet: { id: "cs", service: "gemini", agent: "ki:Gemini", requestedBy: "tom",
      label: "Vorlesung.pdf", createdAt: "2026-09-23T10:00:00Z", revertedAt: null },
      changes: [{ noteId: "n1", path: "Notizen/Photosynthese.md", kind: "CREATED", at: "2026-09-23T10:00:01Z" },
        { noteId: "f1", path: "Anhänge/Vorlesung.pdf", kind: "FILE_CREATED", at: "2026-09-23T10:00:02Z" }] });
    vi.mocked(api.revertChangeSet).mockResolvedValue({ reverted: 1,
      conflicts: [{ path: "Notizen/Zelle.md", reason: "Seit der KI hat jemand weitergeschrieben" }] });
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(AiWorkspace, { vault });

    await fireEvent.click(await screen.findByRole("button", { name: "Änderungen aus Vorlesung.pdf anzeigen" }));
    await screen.findByText("Notizen/Photosynthese.md");
    expect(screen.getByText("Anhänge/Vorlesung.pdf").closest("li")?.textContent).toContain("Original");
    await fireEvent.click(screen.getByRole("button", { name: "Vorlesung.pdf rückgängig machen" }));

    expect(confirm).toHaveBeenCalled();
    await waitFor(() => expect(api.revertChangeSet).toHaveBeenCalledWith("vault", "cs"));
    await screen.findByText(/1 Änderung rückgängig gemacht/);
    expect(screen.getByText(/Notizen\/Zelle\.md – Seit der KI hat jemand weitergeschrieben/)).toBeTruthy();
  });

  it("only offers what the person may do", async () => {
    vi.mocked(api.permissions).mockResolvedValue(["READ"]);
    vi.mocked(api.listChangeSets).mockResolvedValue([{ id: "cs", service: "gemini", agent: "ki:Gemini", requestedBy: "tom",
      label: "Vorlesung.pdf", createdAt: "2026-09-23T10:00:00Z", revertedAt: null }]);
    render(AiWorkspace, { vault });

    await screen.findByText("Vorlesung.pdf");
    expect(screen.queryByLabelText("Dokumente")).toBeNull();
    expect(screen.queryByRole("button", { name: /rückgängig machen/ })).toBeNull();
  });
});
