import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/svelte";
import FileViewer from "../src/lib/components/FileViewer.svelte";
import { api, type Note } from "../src/lib/api";
vi.mock("../src/lib/api", async (original) => ({ ...(await original<typeof import("../src/lib/api")>()), api: { fileBlob: vi.fn() } }));
const file = (path: string): Note => ({ id: "f1", vaultId: "vault", path, noteLevel: 1, createdBy: "Tom",
  createdAt: "2026-09-23T10:00:00Z", kind: "FILE", sha256: "ab", size: 2048, revision: 2 });

beforeEach(() => { vi.resetAllMocks(); URL.revokeObjectURL = vi.fn(); });
afterEach(cleanup);

// A blob: URL shares the dashboard's origin, so an HTML payload named *.pdf must never be framed as HTML.
describe("file preview safety", () => {
  it("frames a PDF only as application/pdf, whatever type the server reported", async () => {
    const created = vi.fn(() => "blob:preview");
    URL.createObjectURL = created;
    vi.mocked(api.fileBlob).mockResolvedValue(new Blob(["<script>alert(1)</script>"], { type: "text/html" }));
    render(FileViewer, { note: file("Anhang/Skript.pdf"), permissions: ["READ"], onChanged: vi.fn(), onDeleted: vi.fn() });
    await screen.findByTitle("Skript.pdf");
    const shown = (created.mock.calls[0] as unknown as [Blob])[0];
    expect(shown.type).toBe("application/pdf");
  });
});
