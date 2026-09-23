import { type App, Modal } from "obsidian";
import { DecisionCountdown } from "./decisionCountdown";
import { deletionConflictText } from "./deletionText";

export const DELETION_DECISION_SECONDS = 15;

/**
 * Anderswo geloescht, hier aber noch nicht uebertragene Aenderungen: die Person entscheidet.
 * Standard ist Loeschen (in Obsidians Papierkorb, wiederherstellbar) - ohne Reaktion nach
 * {@link DELETION_DECISION_SECONDS} Sekunden, beim Schliessen des Dialogs sofort. Nur "Behalten"
 * haelt die Notiz bzw. Datei; sie wird dann neu hochgeladen.
 */
export class DeletionConflictModal extends Modal {
  private readonly countdown: DecisionCountdown;
  private decided = false;

  constructor(
    app: App,
    private readonly path: string,
    private readonly onKeep: () => void,
    private readonly onDelete: () => void,
  ) {
    super(app);
    this.countdown = new DecisionCountdown(DELETION_DECISION_SECONDS, (left) => this.showRemaining(left), () => {
      this.decided = true;
      this.onDelete();
      this.close();
    });
  }

  private deleteButton: HTMLButtonElement | null = null;

  onOpen(): void {
    this.setTitle(`„${this.path.split("/").pop()}“ wurde auf einem anderen Gerät gelöscht`);
    this.contentEl.addClass("stoneintelligence-conflict");
    this.contentEl.createEl("p", {
      text: deletionConflictText(this.path),
    });
    const buttons = this.contentEl.createDiv({ cls: "modal-button-container" });
    const keep = buttons.createEl("button", { text: "Behalten" });
    keep.onclick = () => {
      this.decided = true;
      this.countdown.cancel();
      this.onKeep();
      this.close();
    };
    this.deleteButton = buttons.createEl("button", { cls: "mod-warning" });
    this.deleteButton.onclick = () => this.countdown.finishNow();
    this.countdown.start();
    this.deleteButton.focus();
  }

  private showRemaining(secondsLeft: number): void {
    this.deleteButton?.setText(`Löschen (${Math.max(0, secondsLeft)} s)`);
  }

  onClose(): void {
    this.contentEl.empty();
    if (!this.decided) {
      // Schliessen ohne Wahl = Standard.
      this.countdown.finishNow();
    }
  }
}
