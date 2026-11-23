import { render } from "@testing-library/svelte";
import { describe, expect, it } from "vitest";
import Skeleton from "../src/lib/components/Skeleton.svelte";

describe("skeleton", () => {
  it("hides the shapes from screen readers and names what is loading", () => {
    const { container, getByRole } = render(Skeleton, { kind: "tree", label: "Notizen werden geladen…" });
    expect(getByRole("status").textContent).toContain("Notizen werden geladen…");
    expect(container.querySelector(".skeleton-shapes")?.getAttribute("aria-hidden")).toBe("true");
    expect(container.querySelectorAll(".skeleton-row").length).toBe(5);
  });

  it("draws a document as paragraph lines below the real title bar", () => {
    const { container } = render(Skeleton, { kind: "document", label: "Notiz wird geladen…" });
    expect(container.querySelector(".skeleton-title")).toBeNull();
    expect(container.querySelectorAll(".skeleton-line").length).toBeGreaterThanOrEqual(5);
  });
});
