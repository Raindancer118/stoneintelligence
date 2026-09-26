import { type App, Modal, Setting } from "obsidian";

/** Rueckfrage vor Folgenreichem (jemanden entfernen, Gruppe/Rolle loeschen) - `window.confirm` blockiert Obsidian. */
export function confirmAction(app: App, title: string, text: string, confirmLabel: string): Promise<boolean> {
  return new Promise((resolve) => {
    let answered = false;
    const modal = new (class extends Modal {
      onOpen(): void {
        this.setTitle(title);
        this.contentEl.createEl("p", { text });
        new Setting(this.contentEl)
          .addButton((button) => button.setButtonText("Abbrechen").onClick(() => this.close()))
          .addButton((button) => button.setButtonText(confirmLabel).setWarning().onClick(() => {
            answered = true;
            resolve(true);
            this.close();
          }));
      }

      onClose(): void {
        this.contentEl.empty();
        if (!answered) {
          resolve(false);
        }
      }
    })(app);
    modal.open();
  });
}
