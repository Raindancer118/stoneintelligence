import { type App, Modal, Notice, Setting } from "obsidian";
import { explainAccessError } from "../sync/accessPlan";
import { servicesFor } from "../sync/aiJobText";
import { type AiCapacity, type AiService, HttpError, type NoteApiClient } from "../sync/NoteApiClient";

export interface AiReadTarget { fileId: string; path: string; level: number; }

/**
 * "Mit KI einlesen…" fuer PDFs und Textdateien, die schon im Vault liegen (ADR 0011). Der Server
 * reiht jede Datei mit ihrem eigenen Level ein; hier stehen nur Dienste zur Wahl, die das duerfen.
 */
export class AiReadModal extends Modal {
  private services: AiService[] = [];
  private chosen = "";
  private capacity: AiCapacity | null = null;
  private busy = false;
  private error = "";
  private bodyEl!: HTMLElement;

  constructor(
    app: App,
    private readonly api: NoteApiClient,
    private readonly vaultId: string,
    private readonly targets: AiReadTarget[],
    private readonly openJobs: () => void,
  ) {
    super(app);
  }

  onOpen(): void {
    this.setTitle(this.targets.length === 1 ? `Mit KI einlesen: ${this.targets[0].path}` : `${this.targets.length} Dateien mit KI einlesen`);
    this.bodyEl = this.contentEl.createDiv();
    void this.load();
  }

  onClose(): void {
    this.contentEl.empty();
  }

  private async load(): Promise<void> {
    try {
      this.services = servicesFor(await this.api.aiServices(), this.targets.map((target) => target.level));
      this.chosen = this.services[0]?.id ?? "";
      await this.loadCapacity();
    } catch (error) {
      this.error = describe(error);
    }
    this.render();
  }

  private async loadCapacity(): Promise<void> {
    this.capacity = this.chosen ? await this.api.aiCapacity(this.chosen).catch(() => null) : null;
  }

  private async start(): Promise<void> {
    if (this.busy || !this.chosen) {
      return;
    }
    this.busy = true;
    this.error = "";
    this.render();
    try {
      const jobs = await this.api.readFilesWithAi(this.vaultId, this.chosen, this.targets.map((target) => target.fileId));
      new Notice(`StoneIntelligence: ${jobs.length === 1 ? "Die Datei wird" : `${jobs.length} Dateien werden`} eingelesen. Die neuen Notizen erscheinen von selbst.`);
      this.close();
      this.openJobs();
    } catch (error) {
      this.error = describe(error);
      this.busy = false;
      this.render();
    }
  }

  private render(): void {
    const root = this.bodyEl;
    root.empty();
    if (this.services.length === 0 && !this.error) {
      root.createEl("p", { cls: "setting-item-description", text: "Kein KI-Dienst darf Dateien mit diesem Level verarbeiten." });
      return;
    }
    root.createEl("p", {
      cls: "setting-item-description",
      text: "Die KI liest die Datei und legt daraus Notizen nach Themen an. Das Original bleibt, wie es ist; alles lässt sich unter „KI-Änderungen“ rückgängig machen.",
    });
    new Setting(root).setName("KI-Dienst").addDropdown((dropdown) => {
      for (const service of this.services) {
        dropdown.addOption(service.id, service.name);
      }
      dropdown.setValue(this.chosen).onChange(async (value) => {
        this.chosen = value;
        await this.loadCapacity();
        this.render();
      });
    });
    if (this.capacity?.exhausted) {
      root.createEl("p", {
        cls: "setting-item-description",
        text: `Gerade kein Kontingent frei${this.capacity.availableAgainAt ? ` – wieder ab ${new Date(this.capacity.availableAgainAt).toLocaleString("de-DE")}` : ""}. Der Auftrag wartet so lange und startet dann von selbst.`,
      });
    }
    new Setting(root).addButton((button) => button.setButtonText(this.busy ? "Wird gestartet…" : "Einlesen").setCta()
      .setDisabled(this.busy || !this.chosen).onClick(() => void this.start()));
    if (this.error) {
      root.createEl("p", { cls: "stoneintelligence-invite-error", text: this.error });
    }
  }
}

function describe(error: unknown): string {
  if (error instanceof HttpError) {
    return error.status === 422 ? error.message : explainAccessError(error.status);
  }
  return (error as Error).message ?? String(error);
}
