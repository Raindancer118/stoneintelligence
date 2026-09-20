import { describe, expect, it } from "vitest";
import * as Y from "yjs";
import { decodeContent, prepareUpdate, renderMarkdown, normalizeNotePath } from "../src/lib/noteContent";

describe("browser note content", () => {
  it("saves compatible Yjs updates without mutating the loaded baseline", () => {
    const first = prepareUpdate([], "# Hallo 🌱\nEine Notiz.");
    const doc = decodeContent([first]);
    expect(doc.getText("content").toString()).toBe("# Hallo 🌱\nEine Notiz.");
    const second = prepareUpdate([first], "# Hallo 🌱\nBearbeitet.");
    const peer = decodeContent([first, second, second]);
    expect(peer.getText("content").toString()).toBe("# Hallo 🌱\nBearbeitet.");
    expect(doc.getText("content").toString()).toBe("# Hallo 🌱\nEine Notiz.");
    doc.destroy(); peer.destroy();
  });
  it("handles clearing a note and rejects corrupt stored updates", () => {
    const first = prepareUpdate([], "Content");
    const empty = decodeContent([first, prepareUpdate([first], "")]);
    expect(empty.getText("content").toString()).toBe("");
    empty.destroy();
    expect(() => decodeContent(["broken"])).toThrow();
  });
  it("renders Markdown while removing executable HTML and remote embeds", () => {
    const html = renderMarkdown('# Title\n**Bold**\n<script>alert(1)</script>\n<img src="https://tracker.test/x" onerror="alert(1)">\n[bad](javascript:alert(1))');
    const view = document.createElement("div"); view.innerHTML = html;
    expect(view.querySelector("h1")?.textContent).toBe("Title");
    expect(view.querySelector("strong")?.textContent).toBe("Bold");
    expect(view.querySelector("script,img,[onerror],a[href^='javascript:']")).toBeNull();
  });
  it.each(["../secret.md", "/absolute.md", ".obsidian/config.md", "a/../b.md", "a\\b.md", "a//b.md"])("rejects unsafe path %s", (path) => {
    expect(() => normalizeNotePath(path)).toThrow();
  });
  it("adds the Markdown extension to a folder path", () => {
    expect(normalizeNotePath(" Meetings/Review ")).toBe("Meetings/Review.md");
  });
});
