import { describe, expect, it } from "vitest";
import { answerForDisplay } from "../src/ui/askAnswer";

describe("askAnswer", () => {
  it("should_keepPlainMarkdownAndCitations", () => {
    const text = "**Fett** und eine Liste:\n\n- eins [1]\n- zwei [2, 3]\n\nSiehe [[Photosynthese]].";
    expect(answerForDisplay(text)).toBe(text);
  });

  // Ein Modell, das von Notiztext umgelenkt wurde, darf in Obsidian nichts nachladen oder einbetten.
  it("should_dropImages_embeds_andRawHtml_fromModelOutput", () => {
    const shown = answerForDisplay([
      "Text ![Bild](https://example.test/pixel.png?q=geheim) Ende",
      "![[Geheime Notiz]]",
      "<img src=x onerror=alert(1)><script>alert(1)</script> <b>fett</b>",
      "[Klick](https://example.test/phishing) bleibt Text",
    ].join("\n"));

    expect(shown).not.toMatch(/!\[|<img|<script|<b>|example\.test\/pixel/);
    expect(shown).toContain("Text  Ende");
    expect(shown).toContain("fett");
    expect(shown).toContain("Klick");
    expect(shown).not.toContain("https://");
  });
});
