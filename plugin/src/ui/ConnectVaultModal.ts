import { type App, Modal } from "obsidian";
import { uploadWarning } from "./connectText";

export interface ConnectVaultDetails {
  vaultName: string;
  /** Markdown-Notizen, die schon in diesem Obsidian-Vault liegen - sie werden mit hochgeladen. */
  localNoteCount: number;
  /** Bisher verbundener Vault (anderer als der neue), sonst null. */
  currentVaultName: string | null;
  signedIn: boolean;
}

/**
 * Bestaetigung fuer den Verbinden-Link der Einrichtungsseite. Ein Link allein darf nichts
 * hochladen: vorher steht da, was passiert - vor allem, dass bereits vorhandene Notizen fuer alle
 * Mitglieder des gemeinsamen Vaults sichtbar werden.
 */
export class ConnectVaultModal extends Modal {
  constructor(
    app: App,
    private readonly details: ConnectVaultDetails,
    private readonly onConfirm: () => void,
  ) {
    super(app);
  }

  onOpen(): void {
    const { vaultName, localNoteCount, currentVaultName, signedIn } = this.details;
    this.setTitle(`Mit „${vaultName}“ verbinden?`);
    this.contentEl.addClass("stoneintelligence-connect");
    this.contentEl.createEl("p", {
      text: `Dieser Obsidian-Vault wird mit dem gemeinsamen Vault „${vaultName}“ synchronisiert. Notizen aus „${vaultName}“ werden hierher geladen, Änderungen gehen in beide Richtungen.`,
    });
    if (localNoteCount > 0) {
      this.contentEl.createEl("p", {
        cls: "stoneintelligence-connect-warning",
        text: `${uploadWarning(localNoteCount)} Möchtest du das nicht, öffne zuerst einen leeren Obsidian-Vault und klicke den Link dort erneut.`,
      });
    }
    if (currentVaultName) {
      this.contentEl.createEl("p", {
        cls: "setting-item-description",
        text: `Bisher ist dieser Obsidian-Vault mit „${currentVaultName}“ verbunden. Die Verknüpfung bleibt gespeichert, falls du später zurückwechselst.`,
      });
    }
    const buttons = this.contentEl.createDiv({ cls: "modal-button-container" });
    buttons.createEl("button", { text: "Abbrechen" }).onclick = () => this.close();
    const confirm = buttons.createEl("button", { cls: "mod-cta", text: signedIn ? "Verbinden" : "Anmelden und verbinden" });
    confirm.onclick = () => {
      this.close();
      this.onConfirm();
    };
    confirm.focus();
  }

  onClose(): void {
    this.contentEl.empty();
  }
}
