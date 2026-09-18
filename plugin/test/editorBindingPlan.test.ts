import { describe, expect, it } from "vitest";
import { planEditorBindings } from "../src/sync/editorBindingPlan";

/** Stellvertreter fuer eine CM6-EditorView - verglichen wird nur Instanz-Identitaet. */
const view = (label: string) => ({ label });

describe("planEditorBindings", () => {
  it("bindet eine neu geoeffnete Notiz", () => {
    const a = view("a");
    const plan = planEditorBindings([{ path: "n.md", view: a }], new Map());
    expect(plan.bind).toEqual([{ path: "n.md", view: a }]);
    expect(plan.unbind).toEqual([]);
  });

  it("laesst eine bereits an genau diese View gebundene Notiz unangetastet", () => {
    const a = view("a");
    const plan = planEditorBindings([{ path: "n.md", view: a }], new Map([["n.md", a]]));
    expect(plan.bind).toEqual([]);
    expect(plan.unbind).toEqual([]);
  });

  it("loest eine geschlossene Notiz", () => {
    const a = view("a");
    const plan = planEditorBindings([], new Map([["n.md", a]]));
    expect(plan.unbind).toEqual([{ path: "n.md", view: a }]);
    expect(plan.bind).toEqual([]);
  });

  it("bindet neu, wenn dieselbe Notiz jetzt in einer ANDEREN View liegt", () => {
    const alt = view("alt");
    const neu = view("neu");
    const plan = planEditorBindings([{ path: "n.md", view: neu }], new Map([["n.md", alt]]));
    expect(plan.unbind).toEqual([{ path: "n.md", view: alt }]);
    expect(plan.bind).toEqual([{ path: "n.md", view: neu }]);
  });

  it("bindet mehrere gleichzeitig offene Notizen (geteilte Panes) alle live", () => {
    const a = view("a");
    const b = view("b");
    const plan = planEditorBindings(
      [
        { path: "links.md", view: a },
        { path: "rechts.md", view: b },
      ],
      new Map(),
    );
    expect(plan.bind).toEqual([
      { path: "links.md", view: a },
      { path: "rechts.md", view: b },
    ]);
  });

  it("bindet dieselbe Datei in zwei Panes nur EINMAL", () => {
    // Zwei Views auf dasselbe Dokument wuerden sich sonst um die EINE lokale Cursor-Position
    // in der geteilten Awareness streiten (Muster aus dem Vorgaengerprojekt `stonesync`).
    const erste = view("erste");
    const zweite = view("zweite");
    const plan = planEditorBindings(
      [
        { path: "n.md", view: erste },
        { path: "n.md", view: zweite },
      ],
      new Map(),
    );
    expect(plan.bind).toEqual([{ path: "n.md", view: erste }]);
  });

  it("wechselt sauber, wenn in einem Pane eine andere Notiz geoeffnet wird", () => {
    const pane = view("pane");
    const plan = planEditorBindings([{ path: "neu.md", view: pane }], new Map([["alt.md", pane]]));
    expect(plan.unbind).toEqual([{ path: "alt.md", view: pane }]);
    expect(plan.bind).toEqual([{ path: "neu.md", view: pane }]);
  });
});
