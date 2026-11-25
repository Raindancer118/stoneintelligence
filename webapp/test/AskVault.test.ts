import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/svelte";
import AskVault from "../src/lib/components/AskVault.svelte";
import { api, type AskQuestion } from "../src/lib/api";

vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: {
  aiServices: vi.fn(), askVault: vi.fn(), askHistory: vi.fn(), cancelAsk: vi.fn(), deleteAsk: vi.fn(),
  linkingSettings: vi.fn(), setLinkingConsent: vi.fn(),
} }));

const vault = { id: "vault", name: "Studium", createdAt: "" };
const gemini = { id: "gemini", name: "Gemini", levels: [1], local: false };
const lokal = { id: "lokal", name: "Lokales Modell", levels: [1, 2], local: true };

function question(overrides: Partial<AskQuestion> = {}): AskQuestion {
  return { id: "q1", question: "Wo läuft die Lichtreaktion ab?", service: "gemini", status: "PENDING", progress: null, percent: null,
    error: null, waitingForCapacity: false, availableAt: null, askedAt: "2026-11-25T08:00:00Z", answeredAt: null, answer: null,
    citations: [], restricted: false, omittedForConsent: 0, ...overrides };
}
const answered = (overrides: Partial<AskQuestion> = {}) => question({
  status: "ANSWERED", answer: "Im Thylakoid [1].", answeredAt: "2026-11-25T08:00:20Z",
  citations: [{ n: 1, noteId: "n1", path: "Biologie/Photosynthese.md", heading: "Lichtreaktion", excerpt: "Die Lichtreaktion läuft im Thylakoid ab." }],
  ...overrides });

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(api.aiServices).mockResolvedValue([gemini]);
  vi.mocked(api.askHistory).mockResolvedValue([]);
  vi.mocked(api.linkingSettings).mockResolvedValue({ enabled: false, linkHumanNotes: true, maxLinksPerNote: null, service: null, requestedBy: null, lastRunAt: null, aiConsent: false, aiConsentCount: 0 });
});
afterEach(() => { cleanup(); vi.useRealTimers(); });

async function ask(text = "Wo läuft die Lichtreaktion ab?") {
  await screen.findByLabelText("Deine Frage");
  await fireEvent.input(screen.getByLabelText("Deine Frage"), { target: { value: text } });
  await fireEvent.click(screen.getByRole("button", { name: "Fragen" }));
}

describe("Frag den Vault", () => {
  it("explains that no AI is set up", async () => {
    vi.mocked(api.aiServices).mockResolvedValue([]);
    render(AskVault, { vault, onOpenNote: vi.fn() });

    await screen.findByText(/Auf diesem Server ist noch keine KI eingerichtet/);
    expect(screen.queryByLabelText("Deine Frage")).toBeNull();
  });

  it("shows a skeleton while loading", () => {
    vi.mocked(api.aiServices).mockReturnValue(new Promise(() => {}));
    render(AskVault, { vault, onOpenNote: vi.fn() });

    expect(screen.getByRole("status").textContent).toContain("Frag den Vault wird geladen");
  });

  it("only allows a question of a few words", async () => {
    render(AskVault, { vault, onOpenNote: vi.fn() });
    await screen.findByLabelText("Deine Frage");

    expect((screen.getByRole("button", { name: "Fragen" }) as HTMLButtonElement).disabled).toBe(true);
    await fireEvent.input(screen.getByLabelText("Deine Frage"), { target: { value: "Wie?" } });
    expect((screen.getByRole("button", { name: "Fragen" }) as HTMLButtonElement).disabled).toBe(true);
    await fireEvent.input(screen.getByLabelText("Deine Frage"), { target: { value: "Wie läuft das ab?" } });
    expect((screen.getByRole("button", { name: "Fragen" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("sends the question and shows the answer with citations that open the note", async () => {
    vi.mocked(api.askVault).mockResolvedValue(answered());
    const onOpenNote = vi.fn();
    render(AskVault, { vault, onOpenNote });

    await ask();

    expect(api.askVault).toHaveBeenCalledWith("vault", "Wo läuft die Lichtreaktion ab?", null);
    await screen.findByText(/Im Thylakoid/);
    const sources = screen.getByRole("list", { name: "Quellen" });
    expect(within(sources).getByText("Biologie/Photosynthese.md")).not.toBeNull();
    expect(within(sources).getByText("Die Lichtreaktion läuft im Thylakoid ab.")).not.toBeNull();
    await fireEvent.click(within(sources).getByRole("button", { name: /Biologie\/Photosynthese\.md/ }));
    expect(onOpenNote).toHaveBeenCalledWith("n1", "Biologie/Photosynthese.md");
    await fireEvent.click(screen.getByRole("link", { name: "[1]" }));
    expect(onOpenNote).toHaveBeenCalledTimes(2);
  });

  it("never turns model output into active content", async () => {
    vi.mocked(api.askVault).mockResolvedValue(answered({ answer: "Antwort [1]<img src=x onerror=alert(1)><script>alert(1)</script>[Klick](https://example.test)" }));
    const { container } = render(AskVault, { vault, onOpenNote: vi.fn() });

    await ask();

    await screen.findByRole("list", { name: "Quellen" });
    expect(container.querySelector("img, script")).toBeNull();
    expect(container.innerHTML).not.toContain("example.test");
  });

  it("follows an open question until it is answered", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(api.askVault).mockResolvedValue(question({ status: "RUNNING", progress: "Antwort wird verfasst", percent: 60 }));
    vi.mocked(api.askHistory).mockResolvedValueOnce([]).mockResolvedValue([answered()]);
    render(AskVault, { vault, onOpenNote: vi.fn() });

    await ask();
    await screen.findByText("Antwort wird verfasst · 60 %");
    await vi.advanceTimersByTimeAsync(2500);

    await screen.findByText(/Im Thylakoid/);
  });

  it("says when the readable notes do not answer, and when the answer is hidden", async () => {
    vi.mocked(api.askHistory).mockResolvedValue([
      question({ id: "q2", status: "NOT_ANSWERABLE", question: "Wer wird Meister?" }),
      answered({ id: "q3", question: "Alte Frage zur Zelle?", answer: null, citations: [], restricted: true }),
    ]);
    render(AskVault, { vault, onOpenNote: vi.fn() });

    await screen.findByRole("button", { name: /Wer wird Meister/ });
    expect(screen.getByText("Die Notizen, die du lesen darfst, beantworten diese Frage nicht.")).not.toBeNull();
    await fireEvent.click(screen.getByRole("button", { name: /Alte Frage zur Zelle/ }));
    expect(screen.getByText(/nicht mehr sichtbar/)).not.toBeNull();
  });

  it("tells when notes were left out for lack of consent, and lets the person consent", async () => {
    vi.mocked(api.askHistory).mockResolvedValue([answered({ omittedForConsent: 2 })]);
    vi.mocked(api.setLinkingConsent).mockResolvedValue({ enabled: false, linkHumanNotes: true, maxLinksPerNote: null, service: null, requestedBy: null, lastRunAt: null, aiConsent: true, aiConsentCount: 1 });
    render(AskVault, { vault, onOpenNote: vi.fn() });

    await screen.findByRole("button", { name: /Wo läuft die Lichtreaktion/ });
    expect(screen.getByText(/2 Notizen mit passenden Stellen wurden nicht berücksichtigt/)).not.toBeNull();
    await fireEvent.click(screen.getByLabelText(/Meine Notizen dürfen an externe KI-Anbieter/));

    expect(api.setLinkingConsent).toHaveBeenCalledWith("vault", true);
    await waitFor(() => expect((screen.getByLabelText(/Meine Notizen dürfen an externe KI-Anbieter/) as HTMLInputElement).checked).toBe(true));
  });

  it("says where the question goes, depending on the service", async () => {
    vi.mocked(api.aiServices).mockResolvedValue([gemini, lokal]);
    render(AskVault, { vault, onOpenNote: vi.fn() });

    await screen.findByLabelText("KI-Dienst");
    expect(screen.getByText(/„Lokales Modell“ läuft auf eigener Infrastruktur/)).not.toBeNull();
    expect(screen.queryByLabelText(/Meine Notizen dürfen an externe KI-Anbieter/)).toBeNull();
    await fireEvent.change(screen.getByLabelText("KI-Dienst"), { target: { value: "gemini" } });
    expect(screen.getByText(/Passende Textstellen werden an „Gemini“ übertragen/)).not.toBeNull();
    expect(screen.getByLabelText(/Meine Notizen dürfen an externe KI-Anbieter/)).not.toBeNull();
    vi.mocked(api.askVault).mockResolvedValue(question());
    await ask();
    expect(api.askVault).toHaveBeenCalledWith("vault", "Wo läuft die Lichtreaktion ab?", "gemini");
  });

  it("cancels an open question and deletes a finished one after confirming", async () => {
    vi.mocked(api.askHistory).mockResolvedValue([question({ id: "open", status: "RUNNING" }), answered({ id: "done", question: "Fertige Frage zur Zelle?" })]);
    vi.mocked(api.cancelAsk).mockResolvedValue(question({ id: "open", status: "CANCELLED" }));
    vi.mocked(api.deleteAsk).mockResolvedValue(undefined);
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(AskVault, { vault, onOpenNote: vi.fn() });

    await fireEvent.click(await screen.findByRole("button", { name: "Abbrechen" }));
    expect(api.cancelAsk).toHaveBeenCalledWith("vault", "open");
    await fireEvent.click(screen.getByRole("button", { name: /Fertige Frage zur Zelle/ }));
    await fireEvent.click(screen.getByRole("button", { name: "Löschen" }));

    expect(confirm).toHaveBeenCalled();
    expect(api.deleteAsk).toHaveBeenCalledWith("vault", "done");
    await waitFor(() => expect(screen.queryByText("Fertige Frage zur Zelle?")).toBeNull());
  });

  it("shows why a question was refused", async () => {
    vi.mocked(api.askVault).mockRejectedValue(new Error("Du hast schon 3 offene Fragen"));
    render(AskVault, { vault, onOpenNote: vi.fn() });

    await ask();

    expect((await screen.findByRole("alert")).textContent).toContain("Du hast schon 3 offene Fragen");
  });
});
