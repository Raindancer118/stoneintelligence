import { render } from "@testing-library/svelte";
import { describe, expect, it } from "vitest";
import { excerptParts } from "../src/lib/excerpt";
import Excerpt from "../src/lib/components/Excerpt.svelte";

describe("search excerpt", () => {
  it("splits the server's marked passage into plain and hit parts", () => {
    expect(excerptParts("Die \u0002Bestellmengen\u0003 nach \u0002Andler\u0003.")).toEqual([
      { text: "Die ", hit: false }, { text: "Bestellmengen", hit: true }, { text: " nach ", hit: false },
      { text: "Andler", hit: true }, { text: ".", hit: false },
    ]);
  });

  it("renders note text as text, never as HTML", () => {
    const { container } = render(Excerpt, { text: "<img src=x onerror=alert(1)> \u0002Treffer\u0003" });
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("mark")?.textContent).toBe("Treffer");
    expect(container.textContent).toContain("<img src=x onerror=alert(1)>");
  });
});
