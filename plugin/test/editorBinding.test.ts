// @vitest-environment jsdom
import { Compartment, EditorState } from "@codemirror/state";
import { EditorView } from "@codemirror/view";
import { describe, expect, it } from "vitest";
import { Awareness } from "y-protocols/awareness";
import * as Y from "yjs";
import { applyReadOnly, bindEditorToText } from "../src/sync/editorBinding";

function editorWith(content: string, compartment: Compartment): EditorView {
  const parent = document.createElement("div");
  document.body.appendChild(parent);
  return new EditorView({ state: EditorState.create({ doc: content, extensions: [compartment.of([])] }), parent });
}

function docWith(content: string): Y.Doc {
  const doc = new Y.Doc();
  doc.getText("content").insert(0, content);
  return doc;
}

describe("bindEditorToText", () => {
  // Der Bug hinter "Inhalt doppelt": yCollab geht davon aus, dass Editor und Y.Text beim Binden
  // identisch sind. Wurde gebunden, waehrend der Y.Text noch leer war (Catchup unterwegs), kam der
  // Server-Inhalt als Einfuegung an Position 0 an - vor den bereits sichtbaren Dateiinhalt.
  it("should_notDuplicateContent_when_theServerStateArrivesAfterBinding", () => {
    const compartment = new Compartment();
    const view = editorWith("Hallo Welt", compartment);
    const doc = docWith("Hallo Welt");
    bindEditorToText(view, compartment, doc.getText("content"), new Awareness(doc), "Hallo Welt");

    const remote = new Y.Doc();
    Y.applyUpdate(remote, Y.encodeStateAsUpdate(doc));
    remote.getText("content").insert(10, "!");
    Y.applyUpdate(doc, Y.encodeStateAsUpdate(remote, Y.encodeStateVector(doc)));

    expect(view.state.doc.toString()).toBe("Hallo Welt!");
  });

  it("should_showTheMergedDocumentState_when_theEditorStillShowsTheSavedFile", () => {
    const compartment = new Compartment();
    const view = editorWith("alt", compartment);
    const doc = docWith("alt und neu vom Server");

    bindEditorToText(view, compartment, doc.getText("content"), new Awareness(doc), "alt");

    expect(view.state.doc.toString()).toBe("alt und neu vom Server");
  });

  it("should_keepUnsavedTyping_by_foldingItIntoTheDocument", () => {
    const compartment = new Compartment();
    const view = editorWith("gespeichert + getippt", compartment);
    const doc = docWith("gespeichert");

    bindEditorToText(view, compartment, doc.getText("content"), new Awareness(doc), "gespeichert");

    expect(doc.getText("content").toString()).toBe("gespeichert + getippt");
    expect(view.state.doc.toString()).toBe("gespeichert + getippt");
  });

  it("should_sendLaterTyping_intoTheDocument", () => {
    const compartment = new Compartment();
    const view = editorWith("abc", compartment);
    const doc = docWith("abc");
    bindEditorToText(view, compartment, doc.getText("content"), new Awareness(doc), "abc");

    view.dispatch({ changes: { from: 3, insert: "def" } });

    expect(doc.getText("content").toString()).toBe("abcdef");
  });

  // Rebinding derselben View an eine andere Notiz: der alte Observer darf nicht weiterleben.
  it("should_detachThePreviousText_when_rebindingTheSameView", () => {
    const compartment = new Compartment();
    const view = editorWith("A", compartment);
    const first = docWith("A");
    bindEditorToText(view, compartment, first.getText("content"), new Awareness(first), "A");
    const second = docWith("B");
    bindEditorToText(view, compartment, second.getText("content"), new Awareness(second), "A");

    first.getText("content").insert(1, " geaendert");

    expect(view.state.doc.toString()).toBe("B");
  });
});

describe("applyReadOnly (ADR 0011)", () => {
  it("should_blockEditing_andSayWhy_untilWriteAccessIsBack", () => {
    const compartment = new Compartment();
    const view = editorWith("Nur lesen", compartment);

    applyReadOnly(view, compartment, true);

    expect(view.state.readOnly).toBe(true);
    expect(view.dom.querySelector(".stoneintelligence-readonly-panel")?.textContent).toContain("nur lesen");

    applyReadOnly(view, compartment, false);

    expect(view.state.readOnly).toBe(false);
    expect(view.dom.querySelector(".stoneintelligence-readonly-panel")).toBeNull();
  });
});
