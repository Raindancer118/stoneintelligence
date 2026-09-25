import { type App, Modal } from "obsidian";
import { recommendNewVault, uploadWarning } from "./connectText";

export interface ConnectVaultDetails {
  vaultName: string;
  /** Name dieses Obsidian-Vaults (Ordnername). */
  thisVaultName: string;
  /** Markdown-Notizen, die schon in diesem Obsidian-Vault liegen - sie werden mit hochgeladen. */
  localNoteCount: number;
  /** Bisher verbundener Vault (anderer als der neue), sonst null. */
  currentVaultName: string | null;
  signedIn: boolean;
  /** Vorgeschlagener Ordner fuer einen neuen Obsidian-Vault; null = hier nicht moeglich (Mobile). */
  newVaultPath: string | null;
}

export interface ConnectVaultChoice {
  connectHere(): void;
  /** Ordner-Dialog; liefert den Vault-Pfad fuer die Auswahl oder null (abgebrochen). */
  pickFolder(currentPath: string): string | null;
  createNewVault(path: string): void;
}

/**
 * Bestaetigung fuer den Verbinden-Link der Einrichtungsseite. Ein Link allein darf nichts
 * hochladen: gefragt wird, ob der gemeinsame Vault in einen NEUEN Obsidian-Vault kommt oder in
 * diesen hier - und fuer diesen steht dabei, dass vorhandene Notizen fuer alle Mitglieder sichtbar
 * werden. Liegt hier schon etwas, ist der neue Vault die Voreinstellung.
 */
export class ConnectVaultModal extends Modal {
  constructor(
    app: App,
    private readonly details: ConnectVaultDetails,
    private readonly choice: ConnectVaultChoice,
  ) {
    super(app);
  }

  onOpen(): void {
    const { vaultName, thisVaultName, localNoteCount, currentVaultName, signedIn, newVaultPath } = this.details;
    const preferNew = newVaultPath !== null && recommendNewVault({ localNoteCount, connectedElsewhere: currentVaultName !== null });
    this.setTitle(`Mit „${vaultName}“ verbinden`);
    this.contentEl.addClass("stoneintelligence-connect");
    this.contentEl.createEl("p", {
      text: `Die Notizen aus „${vaultName}“ werden in einen Obsidian-Vault geladen und von da an in beide Richtungen synchronisiert. In welchen?`,
    });

    let newPath = newVaultPath ?? "";
    if (newVaultPath !== null) {
      const section = this.contentEl.createDiv({ cls: "stoneintelligence-connect-option" });
      section.createEl("h4", { text: preferNew ? "Neuer Obsidian-Vault (empfohlen)" : "Neuer Obsidian-Vault" });
      section.createEl("p", {
        cls: "setting-item-description",
        text: "Wird mit dem Plugin angelegt und in einem eigenen Fenster geöffnet. Dort meldest du dich einmal an, dann kommen die Notizen. Dieser Vault bleibt, wie er ist.",
      });
      const row = section.createDiv({ cls: "stoneintelligence-connect-path-row" });
      const input = row.createEl("input", { type: "text", cls: "stoneintelligence-connect-path", value: newVaultPath });
      input.oninput = () => (newPath = input.value);
      row.createEl("button", { text: "Ordner wählen…" }).onclick = () => {
        const picked = this.choice.pickFolder(newPath);
        if (picked) {
          input.value = newPath = picked;
        }
      };
      section.createEl("p", {
        cls: "setting-item-description",
        text: "Ein leerer Ordner wird selbst zum Vault, in einem Ordner mit Inhalt entsteht der Vault als Unterordner.",
      });
    }

    const here = this.contentEl.createDiv({ cls: "stoneintelligence-connect-option" });
    here.createEl("h4", { text: `Dieser Vault („${thisVaultName}“)` });
    if (localNoteCount > 0) {
      here.createEl("p", { cls: "stoneintelligence-connect-warning", text: uploadWarning(localNoteCount) });
    } else {
      here.createEl("p", { cls: "setting-item-description", text: "Er ist noch leer - es wird nichts hochgeladen." });
    }
    if (currentVaultName) {
      here.createEl("p", {
        cls: "stoneintelligence-connect-warning",
        text: `Bisher ist dieser Vault mit „${currentVaultName}“ verbunden. Die Verknüpfung bleibt gespeichert, falls du später zurückwechselst.`,
      });
    }
    if (newVaultPath === null) {
      here.createEl("p", {
        cls: "setting-item-description",
        text: "Möchtest du das nicht, lege in Obsidian zuerst einen neuen, leeren Vault an und öffne den Link dort erneut.",
      });
    }

    const buttons = this.contentEl.createDiv({ cls: "modal-button-container" });
    buttons.createEl("button", { text: "Abbrechen" }).onclick = () => this.close();
    const connectHere = buttons.createEl("button", {
      cls: preferNew ? (localNoteCount > 0 ? "mod-warning" : "") : "mod-cta",
      text: signedIn ? "Diesen Vault verbinden" : "Anmelden und diesen Vault verbinden",
    });
    connectHere.onclick = () => {
      this.close();
      this.choice.connectHere();
    };
    if (newVaultPath !== null) {
      const createNew = buttons.createEl("button", { cls: preferNew ? "mod-cta" : "", text: "Neuen Vault anlegen" });
      createNew.onclick = () => {
        if (!newPath.trim()) {
          return;
        }
        this.close();
        this.choice.createNewVault(newPath.trim());
      };
      (preferNew ? createNew : connectHere).focus();
    } else {
      connectHere.focus();
    }
  }

  onClose(): void {
    this.contentEl.empty();
  }
}
