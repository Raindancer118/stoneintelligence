import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

// Die Webapp wird ohne den Rest des Repos gebaut (Docker-Kontext webapp/), kann die Rechte-Logik
// des Plugins also nicht importieren. Damit es trotzdem nur EINE Wahrheit gibt, muss die Kopie
// Zeichen fuer Zeichen gleich bleiben - Aenderungen immer im Plugin machen und hierher kopieren.
describe("shared logic copied from the plugin", () => {
  it.each(["accessPlan.ts", "historyText.ts"])("%s is an exact copy", (file) => {
    const plugin = readFileSync(resolve(__dirname, `../../plugin/src/sync/${file}`), "utf8");
    const webapp = readFileSync(resolve(__dirname, `../src/lib/${file}`), "utf8");
    expect(webapp).toBe(plugin);
  });
});
