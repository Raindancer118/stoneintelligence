import { type App, type ButtonComponent, Component, MarkdownRenderer, Modal, Setting } from "obsidian";
import { explainAccessError } from "../sync/accessPlan";
import { badge, isOpen, omittedText, statusText } from "../sync/askStatus";
import { type AiService, type AskQuestion, HttpError, type NoteApiClient } from "../sync/NoteApiClient";
import { answerForDisplay } from "./askAnswer";

const MIN_LENGTH = 5;
const MAX_LENGTH = 1000;
const POLL_MS = 2000;

/**
 * "Frag den Vault" (ADR 0017): eine Frage in eigenen Worten, die Antwort entsteht nur aus Notizen, die man lesen darf,
 * und nennt nummerierte Quellen, die die Notiz oeffnen. Dieselben Funktionen wie in der Webapp, einschliesslich des
 * eigenen Verlaufs.
 */
export class AskVaultModal extends Modal {
  private readonly renderer = new Component();
  private services: AiService[] = [];
  private serviceId = "";
  private history: AskQuestion[] = [];
  private openId: string | null = null;
  private text = "";
  private consent: boolean | null = null;
  private busy = false;
  private error = "";
  private timer: ReturnType<typeof setTimeout> | null = null;
  private formEl!: HTMLElement;
  private errorEl!: HTMLElement;
  private historyEl!: HTMLElement;
  private askButton: ButtonComponent | null = null;
  private closed = false;

  constructor(
    app: App,
    private readonly api: NoteApiClient,
    private readonly vaultId: string,
    private readonly openNote: (path: string) => void,
  ) {
    super(app);
  }

  onOpen(): void {
    this.setTitle("Frag den Vault");
    this.renderer.load();
    this.contentEl.createEl("p", {
      cls: "setting-item-description",
      text: "Stell eine Frage in eigenen Worten. Die Antwort entsteht nur aus Notizen, die du lesen darfst, und nennt ihre Quellen. Was die Notizen nicht hergeben, steht dort auch nicht.",
    });
    this.errorEl = this.contentEl.createDiv();
    this.formEl = this.contentEl.createDiv();
    this.historyEl = this.contentEl.createDiv();
    this.formEl.createEl("p", { cls: "setting-item-description", text: "Wird geladen…" });
    void this.load();
  }

  onClose(): void {
    this.closed = true;
    if (this.timer) {
      clearTimeout(this.timer);
    }
    this.renderer.unload();
    this.contentEl.empty();
  }

  private async load(): Promise<void> {
    try {
      [this.services, this.history] = await Promise.all([this.api.aiServices(), this.api.askHistory(this.vaultId)]);
      this.serviceId = (this.services.find((service) => service.local) ?? this.services[0])?.id ?? "";
      this.openId = this.history[0]?.id ?? null;
    } catch (error) {
      this.error = describe(error);
    }
    this.consent = await this.api.linkingSettings(this.vaultId).then((settings) => Boolean(settings.aiConsent), () => null);
    this.render();
    this.schedulePoll();
  }

  private render(): void {
    this.renderError();
    this.renderForm();
    void this.renderHistory();
  }

  private renderError(): void {
    this.errorEl.empty();
    if (this.error) {
      this.errorEl.createEl("p", { cls: "stoneintelligence-invite-error", text: this.error });
    }
  }

  private get service(): AiService | undefined {
    return this.services.find((service) => service.id === this.serviceId);
  }

  private renderForm(): void {
    const root = this.formEl;
    root.empty();
    if (this.services.length === 0) {
      if (!this.error) {
        root.createEl("p", { cls: "setting-item-description", text: "Auf diesem Server ist noch keine KI eingerichtet. Sobald der Betrieb einen KI-Dienst anbindet, kannst du hier Fragen an deinen Vault stellen." });
      }
      return;
    }
    const field = new Setting(root).setName("Deine Frage");
    field.settingEl.addClass("stoneintelligence-ask-field");
    const input = field.controlEl.createEl("textarea", {
      cls: "stoneintelligence-ask-input",
      attr: { rows: "3", maxlength: String(MAX_LENGTH), placeholder: "z. B. Welche Meilensteine nennt der Projektplan?" },
    });
    input.value = this.text;
    input.addEventListener("input", () => {
      this.text = input.value;
      this.askButton?.setDisabled(!this.canAsk());
    });
    input.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        void this.submit();
      }
    });
    if (this.services.length > 1) {
      new Setting(root).setName("KI-Dienst").addDropdown((dropdown) => {
        for (const service of this.services) {
          dropdown.addOption(service.id, service.name);
        }
        dropdown.setValue(this.serviceId).onChange((value) => {
          this.serviceId = value;
          this.renderForm();
        });
      });
    }
    const service = this.service;
    const external = service !== undefined && service.local !== true;
    if (service) {
      root.createEl("p", {
        cls: "setting-item-description",
        text: external
          ? `Passende Textstellen werden an „${service.name}“ übertragen, aber nur aus Notizen, deren Verfasser einer Auswertung durch externe KI-Anbieter zugestimmt haben (siehe Datenschutzerklärung).`
          : `„${service.name}“ läuft auf eigener Infrastruktur, nichts verlässt den Server.`,
      });
    }
    if (external && this.consent !== null) {
      new Setting(root).setName("Meine Notizen dürfen an externe KI-Anbieter").setDesc("Jederzeit widerrufbar.")
        .addToggle((toggle) => toggle.setValue(this.consent === true).onChange((value) => void this.setConsent(value)));
    }
    new Setting(root).addButton((button) => {
      this.askButton = button;
      button.setButtonText(this.busy ? "Wird gestellt…" : "Fragen").setCta().setDisabled(!this.canAsk()).onClick(() => void this.submit());
    });
  }

  private canAsk(): boolean {
    return this.text.trim().length >= MIN_LENGTH && !this.busy;
  }

  private async submit(): Promise<void> {
    if (!this.canAsk()) {
      return;
    }
    this.busy = true;
    this.error = "";
    this.askButton?.setDisabled(true).setButtonText("Wird gestellt…");
    try {
      const created = await this.api.askVault(this.vaultId, this.text.trim(), this.services.length > 1 ? this.serviceId : null);
      this.history = [created, ...this.history.filter((question) => question.id !== created.id)];
      this.openId = created.id;
      this.text = "";
      this.schedulePoll();
    } catch (error) {
      this.error = describe(error);
    }
    this.busy = false;
    this.render();
  }

  private async setConsent(value: boolean): Promise<void> {
    try {
      this.consent = Boolean((await this.api.setLinkingConsent(this.vaultId, value)).aiConsent);
      this.error = "";
    } catch (error) {
      this.error = describe(error);
    }
    this.renderError();
  }

  /** Solange eine Frage wartet oder laeuft, alle paar Sekunden nachsehen - danach Ruhe. */
  private schedulePoll(): void {
    if (this.timer) {
      clearTimeout(this.timer);
    }
    this.timer = null;
    if (this.closed || !this.history.some(isOpen)) {
      return;
    }
    this.timer = setTimeout(() => void this.poll(), POLL_MS);
  }

  private async poll(): Promise<void> {
    try {
      this.history = await this.api.askHistory(this.vaultId);
      await this.renderHistory();
    } catch {
      // Naechster Takt versucht es erneut.
    }
    this.schedulePoll();
  }

  private async renderHistory(): Promise<void> {
    const root = this.historyEl;
    root.empty();
    if (this.services.length === 0) {
      return;
    }
    root.createEl("h4", { text: "Deine Fragen" });
    if (this.history.length === 0) {
      root.createEl("p", { cls: "setting-item-description", text: "Du hast noch nichts gefragt. Fragen und Antworten siehst nur du." });
      return;
    }
    for (const question of this.history) {
      const expanded = this.openId === question.id;
      new Setting(root).setName(question.question)
        .setDesc(`${this.serviceName(question.service)} · ${new Date(question.askedAt).toLocaleString("de-DE", { dateStyle: "medium", timeStyle: "short" })} · ${badge(question)}`)
        .addButton((button) => button.setButtonText(expanded ? "Ausblenden" : "Anzeigen").onClick(() => {
          this.openId = expanded ? null : question.id;
          void this.renderHistory();
        }));
      if (expanded) {
        await this.renderAnswer(root.createDiv({ cls: "stoneintelligence-ask-answer" }), question);
      }
    }
  }

  private async renderAnswer(el: HTMLElement, question: AskQuestion): Promise<void> {
    if (isOpen(question)) {
      el.createEl("p", { cls: "setting-item-description", text: statusText(question) });
      new Setting(el).addButton((button) => button.setButtonText("Abbrechen").onClick(() => void this.cancel(question)));
      return;
    }
    if (question.status === "ANSWERED" && question.answer) {
      await MarkdownRenderer.render(this.app, answerForDisplay(question.answer), el.createDiv(), "", this.renderer);
      for (const citation of question.citations) {
        new Setting(el).setName(`[${citation.n}] ${citation.path}`)
          .setDesc(`${citation.heading && !citation.path.endsWith(`${citation.heading}.md`) ? `${citation.heading} · ` : ""}${citation.excerpt}`)
          .addButton((button) => button.setButtonText("Öffnen").onClick(() => {
            this.openNote(citation.path);
            this.close();
          }));
      }
    } else if (question.restricted) {
      el.createEl("p", { text: "Diese Antwort ist nicht mehr sichtbar, weil du eine der zitierten Notizen nicht mehr lesen darfst." });
    } else if (question.status === "NOT_ANSWERABLE") {
      el.createEl("p", { text: "Die Notizen, die du lesen darfst, beantworten diese Frage nicht." });
    } else if (question.status === "FAILED") {
      el.createEl("p", { cls: "stoneintelligence-invite-error", text: statusText(question) });
    } else if (question.status === "CANCELLED") {
      el.createEl("p", { cls: "setting-item-description", text: "Abgebrochen." });
    }
    const omitted = omittedText(question.omittedForConsent);
    if (omitted) {
      el.createEl("p", { cls: "setting-item-description", text: omitted });
    }
    new Setting(el).addButton((button) => button.setButtonText("Löschen").setWarning().onClick(() => void this.remove(question)));
  }

  private async cancel(question: AskQuestion): Promise<void> {
    try {
      const updated = await this.api.cancelAsk(this.vaultId, question.id);
      this.history = this.history.map((entry) => (entry.id === updated.id ? updated : entry));
      this.error = "";
    } catch (error) {
      this.error = describe(error);
    }
    this.renderError();
    await this.renderHistory();
  }

  private async remove(question: AskQuestion): Promise<void> {
    try {
      await this.api.deleteAsk(this.vaultId, question.id);
      this.history = this.history.filter((entry) => entry.id !== question.id);
      this.error = "";
    } catch (error) {
      this.error = describe(error);
    }
    this.renderError();
    await this.renderHistory();
  }

  private serviceName(id: string): string {
    return this.services.find((service) => service.id === id)?.name ?? id;
  }
}

function describe(error: unknown): string {
  if (error instanceof HttpError) {
    return error.status === 422 ? error.message : explainAccessError(error.status);
  }
  return (error as Error).message ?? String(error);
}
