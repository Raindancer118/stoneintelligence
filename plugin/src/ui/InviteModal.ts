import { type App, Modal, Setting } from "obsidian";
import type { InviteAccess, NoteApiClient, PendingInvitation, PersonSuggestion } from "../sync/NoteApiClient";

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

/**
 * Mitbearbeiter einladen, ohne Obsidian zu verlassen - dasselbe Prinzip wie im Web-Dashboard:
 * EIN Feld. Einen Namen tippen findet bestehende Konten (ein Klick fuegt hinzu), eine vollstaendige
 * E-Mail-Adresse verschickt eine Einladung. Wer schon ein Konto hat, wird dabei direkt Mitglied.
 */
export class InviteModal extends Modal {
  private query = "";
  private access: InviteAccess = "EDIT";
  private suggestions: PersonSuggestion[] = [];
  private pending: PendingInvitation[] = [];
  private busy = false;
  private message = "";
  private error = "";
  private searchTimer: number | null = null;
  private searchGeneration = 0;
  private resultsEl!: HTMLElement;
  private inputEl: HTMLInputElement | null = null;

  constructor(
    app: App,
    private readonly api: NoteApiClient,
    private readonly vaultId: string,
    private readonly vaultName: string,
  ) {
    super(app);
  }

  onOpen(): void {
    this.setTitle(`Mitbearbeiter für „${this.vaultName}“ einladen`);
    this.contentEl.addClass("stoneintelligence-invite");
    this.contentEl.createEl("p", {
      cls: "setting-item-description",
      text: "Namen eingeben, um ein bestehendes Konto zu finden – oder eine E-Mail-Adresse, um jemanden ohne Konto einzuladen.",
    });
    new Setting(this.contentEl)
      .setName("Name oder E-Mail-Adresse")
      .addText((text) => {
        this.inputEl = text.inputEl;
        text.setPlaceholder("z. B. Anna oder anna@beispiel.de").onChange((value) => {
          this.query = value.trim();
          this.onQueryChanged();
        });
        text.inputEl.addEventListener("keydown", (event) => {
          if (event.key === "Enter" && EMAIL.test(this.query)) {
            void this.inviteEmail();
          }
        });
        window.setTimeout(() => text.inputEl.focus(), 0);
      });
    new Setting(this.contentEl)
      .setName("Rechte")
      .setDesc("Eingeladene Personen verwalten keine Mitglieder.")
      .addDropdown((dropdown) => dropdown
        .addOption("EDIT", "Darf bearbeiten")
        .addOption("READ", "Darf nur lesen")
        .setValue(this.access)
        .onChange((value) => (this.access = value as InviteAccess)));
    this.resultsEl = this.contentEl.createDiv();
    this.render();
    void this.loadPending();
  }

  onClose(): void {
    window.clearTimeout(this.searchTimer ?? undefined);
    this.contentEl.empty();
  }

  private async loadPending(): Promise<void> {
    try {
      this.pending = await this.api.listInvitations(this.vaultId);
    } catch (error) {
      this.error = (error as Error).message;
    }
    this.render();
  }

  private onQueryChanged(): void {
    this.message = "";
    this.error = "";
    window.clearTimeout(this.searchTimer ?? undefined);
    const generation = ++this.searchGeneration;
    if (this.query.length < 2) {
      this.suggestions = [];
      this.render();
      return;
    }
    this.render();
    this.searchTimer = window.setTimeout(async () => {
      try {
        const found = await this.api.searchPeople(this.vaultId, this.query);
        if (generation === this.searchGeneration) {
          this.suggestions = found;
        }
      } catch (error) {
        if (generation === this.searchGeneration) {
          this.error = (error as Error).message;
        }
      }
      this.render();
    }, 250);
  }

  private async add(person: PersonSuggestion): Promise<void> {
    await this.run(async () => {
      const result = await this.api.addPerson(this.vaultId, person.username, this.access);
      return result.status === "ALREADY_MEMBER" ? `${person.name} ist bereits Mitglied.` : `${person.name} ist jetzt Mitglied.`;
    });
  }

  private async inviteEmail(): Promise<void> {
    const email = this.query;
    await this.run(async () => {
      const result = await this.api.inviteByEmail(this.vaultId, email, this.access);
      await this.loadPending();
      if (result.status === "INVITED") {
        return `Einladung an ${email} verschickt.`;
      }
      return result.status === "ADDED"
        ? `${result.displayName} hatte schon ein Konto und ist jetzt Mitglied.`
        : `${result.displayName} ist bereits Mitglied.`;
    });
  }

  private async revoke(invitation: PendingInvitation): Promise<void> {
    await this.run(async () => {
      await this.api.revokeInvitation(this.vaultId, invitation.id);
      await this.loadPending();
      return `Einladung an ${invitation.email} zurückgezogen.`;
    });
  }

  private async run(action: () => Promise<string>): Promise<void> {
    if (this.busy) {
      return;
    }
    this.busy = true;
    this.error = "";
    this.render();
    try {
      this.message = await action();
      this.suggestions = [];
      // Erledigt - Feld leeren, damit der naechste Name direkt getippt werden kann.
      this.query = "";
      this.searchGeneration++;
      if (this.inputEl) {
        this.inputEl.value = "";
        this.inputEl.focus();
      }
    } catch (error) {
      this.error = (error as Error).message;
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private render(): void {
    const root = this.resultsEl;
    if (!root) {
      return;
    }
    root.empty();
    for (const person of this.suggestions) {
      const row = new Setting(root).setName(person.name).setDesc(person.maskedEmail);
      if (person.alreadyMember) {
        row.setDesc(`${person.maskedEmail} · bereits Mitglied`);
      } else {
        row.addButton((button) => button.setButtonText("Hinzufügen").setDisabled(this.busy).onClick(() => void this.add(person)));
      }
    }
    if (EMAIL.test(this.query)) {
      new Setting(root)
        .setName(`Einladung an ${this.query}`)
        .setDesc("Hat die Adresse schon ein Konto, wird die Person direkt hinzugefügt. Sonst bekommt sie einen Link zum Anmelden oder Konto anlegen.")
        .addButton((button) => button.setButtonText(this.busy ? "Wird gesendet…" : "Einladen").setCta().setDisabled(this.busy)
          .onClick(() => void this.inviteEmail()));
    } else if (this.query.length >= 2 && this.suggestions.length === 0) {
      root.createEl("p", { cls: "setting-item-description", text: "Kein Konto gefunden. Mit der vollständigen E-Mail-Adresse kannst du die Person einladen." });
    }
    if (this.message) {
      root.createEl("p", { cls: "stoneintelligence-invite-message", text: this.message });
    }
    if (this.error) {
      root.createEl("p", { cls: "stoneintelligence-invite-error", text: this.error });
    }
    if (this.pending.length > 0) {
      root.createEl("h4", { cls: "stoneintelligence-invite-heading", text: "Offene Einladungen" });
      const date = new Intl.DateTimeFormat("de-DE", { day: "numeric", month: "long" });
      for (const invitation of this.pending) {
        new Setting(root)
          .setName(invitation.email)
          .setDesc(`${invitation.access === "READ" ? "lesen" : "bearbeiten"} · gültig bis ${date.format(new Date(invitation.expiresAt))}`)
          .addButton((button) => button.setButtonText("Zurückziehen").onClick(() => void this.revoke(invitation)));
      }
    }
  }
}
