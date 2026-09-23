import { type App, Notice, type Plugin, PluginSettingTab, Setting, setIcon } from "obsidian";
import { DEFAULT_SETTINGS, type StoneIntelligenceSettings, wsUrlFor } from "../settings";
import type { VaultSummary } from "../sync/NoteApiClient";
import type { SyncActivity } from "../sync/SyncActivity";
import { presentStatus } from "./statusPresentation";

export interface SettingsHost {
  readonly settings: StoneIntelligenceSettings;
  readonly activity: SyncActivity;
  isLoggedIn(): boolean;
  accountName(): string | null;
  pendingCount(): number;
  linkedCount(): number;
  login(): Promise<void>;
  logout(): Promise<void>;
  listVaults(): Promise<VaultSummary[]>;
  selectVault(vault: VaultSummary): Promise<void>;
  createVault(name: string): Promise<void>;
  syncNow(): void;
  setPaused(paused: boolean): void;
  /** Speichert und uebernimmt geaenderte Verbindungsdaten (Clients neu aufbauen, neu verbinden). */
  saveConnectionSettings(): Promise<void>;
  saveSettings(): Promise<void>;
  connectionConfigJson(): string;
  applyConnectionConfig(json: string): Promise<void>;
  canInvite(): boolean;
  openInvite(): void;
}

/**
 * Einstellungen in der Reihenfolge, in der man sie braucht: Zustand -> Konto -> Vault ->
 * Synchronisation. Server-Adressen und OIDC-Daten sind voreingestellt und liegen unter
 * "Erweitert" - frueher standen sie als erste fuenf Pflichtfelder ganz oben, und der Vault musste
 * als UUID abgetippt werden.
 */
export class StoneIntelligenceSettingTab extends PluginSettingTab {
  private advancedOpen = false;
  private unsubscribe: (() => void) | null = null;

  constructor(
    app: App,
    plugin: Plugin,
    private readonly host: SettingsHost,
  ) {
    super(app, plugin);
  }

  display(): void {
    const { containerEl } = this;
    containerEl.empty();
    containerEl.addClass("stoneintelligence-settings");

    this.renderStatus(containerEl);
    this.renderAccount(containerEl);
    this.renderVault(containerEl);
    this.renderSync(containerEl);
    this.renderAdvanced(containerEl);

    this.unsubscribe?.();
    const statusEl = containerEl.querySelector<HTMLElement>(".stoneintelligence-settings-status");
    this.unsubscribe = this.host.activity.subscribe(() => {
      if (statusEl?.isConnected) {
        this.fillStatus(statusEl);
      }
    });
  }

  hide(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  private renderStatus(containerEl: HTMLElement): void {
    const statusEl = containerEl.createDiv({ cls: "stoneintelligence-settings-status" });
    this.fillStatus(statusEl);
  }

  private fillStatus(statusEl: HTMLElement): void {
    const status = presentStatus(this.host.activity, { pending: this.host.pendingCount() });
    statusEl.empty();
    statusEl.className = `stoneintelligence-settings-status is-${status.tone}`;
    const icon = statusEl.createSpan({ cls: "stoneintelligence-hero-icon" });
    setIcon(icon, status.icon);
    icon.toggleClass("is-spinning", status.spinning);
    const text = statusEl.createDiv();
    text.createDiv({ cls: "stoneintelligence-hero-label", text: status.label });
    text.createDiv({ cls: "stoneintelligence-hero-detail", text: status.tooltip });
  }

  private renderAccount(containerEl: HTMLElement): void {
    new Setting(containerEl).setName("Konto").setHeading();
    if (this.host.isLoggedIn()) {
      new Setting(containerEl)
        .setName(`Angemeldet als ${this.host.accountName() ?? "unbekannt"}`)
        .setDesc("Die Anmeldung gilt nur auf diesem Gerät.")
        .addButton((button) =>
          button.setButtonText("Abmelden").onClick(async () => {
            await this.host.logout();
            this.display();
          }),
        );
      return;
    }
    new Setting(containerEl)
      .setName("Anmelden")
      .setDesc("Öffnet die Anmeldung im Browser und kehrt danach automatisch hierher zurück.")
      .addButton((button) =>
        button.setButtonText("Anmelden").setCta().onClick(async () => {
          button.setDisabled(true).setButtonText("Warte auf Anmeldung…");
          try {
            await this.host.login();
          } catch (error) {
            new Notice(`StoneIntelligence: Anmeldung fehlgeschlagen – ${(error as Error).message}`);
          }
          this.display();
        }),
      );
  }

  private renderVault(containerEl: HTMLElement): void {
    new Setting(containerEl).setName("Vault").setHeading();
    const settings = this.host.settings;
    if (!this.host.isLoggedIn()) {
      new Setting(containerEl)
        .setName("Vault auswählen")
        .setDesc("Nach der Anmeldung wählst du hier, mit welchem gemeinsamen Vault dieser Obsidian-Vault synchronisiert.")
        .setDisabled(true);
      return;
    }

    const select = new Setting(containerEl)
      .setName("Synchronisieren mit")
      .setDesc(settings.vaultId
        ? `${this.host.linkedCount()} Notizen verknüpft. Ein Wechsel behält die Verknüpfungen beider Vaults getrennt.`
        : "Wähle den gemeinsamen Vault, in den dieser Obsidian-Vault synchronisiert wird.");
    select.addDropdown((dropdown) => {
      dropdown.addOption("", settings.vaultId ? (settings.vaultName || "Aktueller Vault") : "Lade Vaults…");
      if (settings.vaultId) {
        dropdown.addOption(settings.vaultId, settings.vaultName || settings.vaultId);
        dropdown.setValue(settings.vaultId);
      }
      dropdown.setDisabled(true);
      void this.host.listVaults().then(
        (vaults) => {
          dropdown.selectEl.empty();
          if (!settings.vaultId) {
            dropdown.addOption("", vaults.length > 0 ? "Vault wählen…" : "Noch kein Vault – unten anlegen");
          }
          for (const vault of vaults) {
            dropdown.addOption(vault.id, vault.name);
          }
          if (settings.vaultId && !vaults.some((vault) => vault.id === settings.vaultId)) {
            dropdown.addOption(settings.vaultId, `${settings.vaultName || settings.vaultId} (kein Zugriff mehr)`);
          }
          dropdown.setValue(settings.vaultId);
          dropdown.setDisabled(false);
          dropdown.onChange(async (vaultId) => {
            const vault = vaults.find((candidate) => candidate.id === vaultId);
            if (vault) {
              await this.host.selectVault(vault);
              new Notice(`StoneIntelligence: synchronisiert jetzt mit „${vault.name}“.`);
              this.display();
            }
          });
        },
        (error: Error) => {
          dropdown.selectEl.empty();
          dropdown.addOption(settings.vaultId, settings.vaultId ? (settings.vaultName || settings.vaultId) : "Vaults nicht ladbar");
          select.setDesc(`Vault-Liste konnte nicht geladen werden (${error.message}).`);
        },
      );
    });

    if (settings.vaultId) {
      new Setting(containerEl)
        .setName("Mitbearbeiter")
        .setDesc(this.host.canInvite()
          ? "Bestehende Konten hinzufügen oder Personen per E-Mail einladen."
          : "Nur wer diesen Vault verwaltet, kann Personen einladen.")
        .addButton((button) => button.setButtonText("Einladen…").setDisabled(!this.host.canInvite())
          .onClick(() => this.host.openInvite()));
    }

    let newVaultName = "";
    new Setting(containerEl)
      .setName("Neuen Vault anlegen")
      .setDesc("Du bekommst automatisch volle Rechte darin und kannst danach Mitbearbeiter einladen.")
      .addText((text) => text.setPlaceholder("Name").onChange((value) => (newVaultName = value)))
      .addButton((button) =>
        button.setButtonText("Anlegen").onClick(async () => {
          if (!newVaultName.trim()) {
            new Notice("StoneIntelligence: Bitte einen Namen eingeben.");
            return;
          }
          try {
            await this.host.createVault(newVaultName.trim());
            new Notice(`StoneIntelligence: Vault „${newVaultName.trim()}“ angelegt und ausgewählt.`);
            this.display();
          } catch (error) {
            new Notice(`StoneIntelligence: Vault anlegen fehlgeschlagen – ${(error as Error).message}`);
          }
        }),
      );
  }

  private renderSync(containerEl: HTMLElement): void {
    new Setting(containerEl).setName("Synchronisation").setHeading();
    const settings = this.host.settings;

    new Setting(containerEl)
      .setName("Automatisch synchronisieren")
      .setDesc("Aus = pausiert. Lokale Änderungen bleiben erhalten und werden beim Fortsetzen übertragen.")
      .addToggle((toggle) =>
        toggle.setValue(!settings.paused).onChange((enabled) => {
          this.host.setPaused(!enabled);
        }),
      )
      .addExtraButton((button) =>
        button.setIcon("refresh-cw").setTooltip("Jetzt synchronisieren").onClick(() => this.host.syncNow()),
      );

    new Setting(containerEl)
      .setName("Ausgeschlossene Ordner")
      .setDesc("Ein Ordner pro Zeile. Notizen darin werden weder hoch- noch heruntergeladen.")
      .addTextArea((text) => {
        text.setPlaceholder("Privat\nVorlagen").setValue(settings.excludedFolders.join("\n"));
        text.inputEl.rows = 3;
        text.inputEl.addEventListener("blur", async () => {
          settings.excludedFolders = text.getValue().split("\n").map((line) => line.trim()).filter(Boolean);
          await this.host.saveSettings();
        });
      });
  }

  private renderAdvanced(containerEl: HTMLElement): void {
    const details = containerEl.createEl("details", { cls: "stoneintelligence-advanced" });
    details.open = this.advancedOpen;
    details.addEventListener("toggle", () => (this.advancedOpen = details.open));
    details.createEl("summary", { text: "Erweitert: Server & Anmeldung" });
    const settings = this.host.settings;

    const text = (name: string, desc: string, key: "platformApiUrl" | "platformWsUrl" | "oidcIssuerUrl" | "oidcClientId", placeholder: string) =>
      new Setting(details).setName(name).setDesc(desc).addText((input) => {
        input.setPlaceholder(placeholder).setValue(settings[key]);
        input.inputEl.addEventListener("change", async () => {
          settings[key] = input.getValue().trim();
          await this.host.saveConnectionSettings();
        });
      });

    text("Server", "Adresse der StoneIntelligence-Plattform.", "platformApiUrl", DEFAULT_SETTINGS.platformApiUrl);
    text("WebSocket", "Leer lassen, um sie aus der Server-Adresse abzuleiten.", "platformWsUrl", wsUrlFor(settings));
    text("OIDC-Issuer", "Nach einer Änderung bitte neu anmelden.", "oidcIssuerUrl", DEFAULT_SETTINGS.oidcIssuerUrl);
    text("OIDC-Client-ID", "Öffentlicher Client (PKCE), kein Secret nötig.", "oidcClientId", DEFAULT_SETTINGS.oidcClientId);

    new Setting(details)
      .setName("Verbindungsdaten teilen")
      .setDesc("Server, Vault und Anmeldedaten als Text zwischen Geräten übertragen (ohne Login-Tokens).")
      .addButton((button) =>
        button.setButtonText("Kopieren").onClick(async () => {
          await navigator.clipboard.writeText(this.host.connectionConfigJson());
          new Notice("StoneIntelligence: Verbindungsdaten kopiert.");
        }),
      )
      .addButton((button) =>
        button.setButtonText("Einfügen").onClick(async () => {
          try {
            await this.host.applyConnectionConfig(await navigator.clipboard.readText());
            new Notice("StoneIntelligence: Verbindungsdaten übernommen.");
            this.display();
          } catch (error) {
            new Notice(`StoneIntelligence: Verbindungsdaten ungültig – ${(error as Error).message}`);
          }
        }),
      );

    new Setting(details)
      .setName("Auf Standard zurücksetzen")
      .setDesc("Stellt Server- und Anmeldeadressen der gehosteten Instanz wieder her.")
      .addButton((button) =>
        button.setButtonText("Zurücksetzen").setWarning().onClick(async () => {
          settings.platformApiUrl = DEFAULT_SETTINGS.platformApiUrl;
          settings.platformWsUrl = DEFAULT_SETTINGS.platformWsUrl;
          settings.oidcIssuerUrl = DEFAULT_SETTINGS.oidcIssuerUrl;
          settings.oidcClientId = DEFAULT_SETTINGS.oidcClientId;
          await this.host.saveConnectionSettings();
          this.display();
        }),
      );
  }
}
