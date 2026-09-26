import { type Compartment, EditorState } from "@codemirror/state";
import { EditorView, showPanel } from "@codemirror/view";
import { yCollab } from "y-codemirror.next";
import type { Awareness } from "y-protocols/awareness";
import type * as Y from "yjs";
import { applyTextChange, textChanges } from "./textDiff";

/**
 * Bindet eine CodeMirror-6-View zeichengenau an einen Y.Text (yCollab inkl. Remote-Cursor).
 *
 * <p>yCollab setzt voraus, dass Editor und Y.Text beim Binden IDENTISCH sind - es gleicht nicht
 * selbst ab, sondern uebertraegt ab dann nur noch Differenzen. Frueher wurde sofort gebunden,
 * waehrend der Server-Catchup noch lief: der dann eintreffende Inhalt landete als Einfuegung vor
 * dem schon sichtbaren Text (Notiz doppelt). Deshalb hier zuerst angleichen, dann - synchron, ohne
 * dass dazwischen eine Eingabe passieren kann - binden:
 *
 * <ul>
 *   <li>zeigt der Editor noch den gespeicherten Dateistand, ist der Y.Text die Wahrheit (er enthaelt
 *   Datei + alles, was der Server beigetragen hat) - der Editor wird darauf gebracht;</li>
 *   <li>weicht der Editor von der Datei ab, hat der Nutzer seit dem letzten Autosave getippt - das
 *   wird als echte Aenderung in den Y.Text uebernommen, nicht verworfen.</li>
 * </ul>
 *
 * @param savedFileContent Dateiinhalt, auf dem der Y.Text vor dem Binden aufgebaut wurde
 */
export function bindEditorToText(
  view: EditorView,
  compartment: Compartment,
  text: Y.Text,
  awareness: Awareness,
  savedFileContent: string,
): void {
  // ZWEI getrennte Transaktionen zum Loesen: `ySync` aus y-codemirror.next ist ein modulweites
  // ViewPlugin-Singleton. Direkt von einem yCollab auf ein anderes umkonfiguriert, erzeugt
  // CodeMirror es NICHT neu - der alte Y.Text-Observer lebte weiter und schrieb Aenderungen der
  // vorher gebundenen Notiz in diesen Editor.
  view.dispatch({ effects: compartment.reconfigure([]) });

  const editorContent = view.state.doc.toString();
  const docContent = text.toString();
  if (editorContent !== docContent) {
    if (editorContent === savedFileContent) {
      view.dispatch({ changes: textChanges(editorContent, docContent) });
    } else {
      applyTextChange(text, editorContent);
    }
  }
  view.dispatch({ effects: compartment.reconfigure(yCollab(text, awareness)) });
}

export function unbindEditor(view: EditorView, compartment: Compartment): void {
  view.dispatch({ effects: compartment.reconfigure([]) });
}

/**
 * Schreibschutz fuer Notizen, die man nur lesen darf (ADR 0011): der Editor nimmt keine Eingaben
 * an und sagt oben, warum. Der Server verwirft Aenderungen ohnehin - ohne Sperre saehe man seine
 * Tipperei, die nie irgendwo ankommt.
 */
export function applyReadOnly(view: EditorView, compartment: Compartment, readOnly: boolean): void {
  view.dispatch({
    effects: compartment.reconfigure(readOnly
      ? [EditorState.readOnly.of(true), EditorView.editable.of(false), showPanel.of(readOnlyPanel)]
      : []),
  });
}

function readOnlyPanel(): { dom: HTMLElement; top: boolean } {
  const dom = document.createElement("div");
  dom.className = "stoneintelligence-readonly-panel";
  dom.textContent = "Diese Notiz darfst du nur lesen. Wer sie verwaltet, kann dir Bearbeiten freigeben.";
  return { dom, top: true };
}
