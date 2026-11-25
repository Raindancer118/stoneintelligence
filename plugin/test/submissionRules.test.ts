import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const root = join(__dirname, "..");
const manifest = JSON.parse(readFileSync(join(root, "manifest.json"), "utf8"));

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? sources(path) : path.endsWith(".ts") ? [path] : [];
  });
}
const files = sources(join(root, "src")).map((path) => ({ path, text: readFileSync(path, "utf8") }));

describe("Obsidian community directory rules", () => {
  it("manifest id and name follow the naming rules", () => {
    expect(manifest.id).toMatch(/^[a-z-]+$/);
    expect(manifest.id).not.toContain("obsidian");
    expect(manifest.id.endsWith("plugin")).toBe(false);
    expect(manifest.name.toLowerCase()).not.toMatch(/obsidian|plugin/);
  });

  it("description is short, plain English, and ends with a period", () => {
    expect(manifest.description.length).toBeLessThanOrEqual(250);
    expect(manifest.description.endsWith(".")).toBe(true);
    expect(manifest.description).not.toMatch(/^This is a plugin/i);
    expect(manifest.description).toMatch(/^[\x20-\x7e]+$/);
    expect(manifest.description).toMatch(/^[A-Z][a-z]+ /);
  });

  it("command ids do not repeat the plugin id and set no default hotkeys", () => {
    const text = files.map((f) => f.text).join("\n");
    const ids = [...text.matchAll(/addCommand\(\{[^]*?\bid:\s*"([^"]+)"/g)].map((m) => m[1]);
    expect(ids.length).toBeGreaterThan(5);
    for (const id of ids) {
      expect(id, id).not.toContain(manifest.id);
    }
    expect(text).not.toMatch(/\bhotkeys\s*:/);
  });

  it("only errors reach the console", () => {
    for (const { path, text } of files) {
      expect(text, path).not.toMatch(/console\.(log|info|debug|warn)\(/);
    }
  });

  it("keeps the root manifest.json the directory reads in sync with the plugin manifest", () => {
    const rootManifest = JSON.parse(readFileSync(join(root, "..", "manifest.json"), "utf8"));
    expect(rootManifest).toEqual(manifest);
  });

  it("never writes dynamic HTML", () => {
    for (const { path, text } of files) {
      expect(text, path).not.toMatch(/innerHTML|outerHTML|insertAdjacentHTML/);
    }
  });
});
