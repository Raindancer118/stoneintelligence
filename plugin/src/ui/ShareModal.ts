import { type App, Modal, Notice, Setting } from "obsidian";
import {
  type AccessReport, describeScope, describeSource, explainAccessError, type Grant, permissionsFor,
  permissionsLabel, PRESETS, presetOf, type PresetId, type ScopeType,
} from "../sync/accessPlan";
import { type GrantChange, HttpError, type NoteApiClient, type VaultGroup, type VaultMember } from "../sync/NoteApiClient";

/** Ein Eintrag (Notiz/Datei, per Id) oder ein Ordner (`""` = der ganze Vault). */
export type ShareTarget = { kind: "entry"; noteId: string; path: string } | { kind: "folder"; path: string };

interface Who { scopeType: ScopeType; subject: string | null; label: string; }

/**
 * "Freigabe…" aus dem Kontextmenue (ADR 0011): wer an dieser Stelle was darf und woher, und fuer
 * Verwaltende das Aendern - Personen, Gruppen oder alle Mitglieder, mit Voreinstellungen. Bei
 * mehreren ausgewaehlten Eintraegen gilt eine Aenderung fuer alle.
 */
export class ShareModal extends Modal {
  private report: AccessReport | null = null;
  private members: VaultMember[] = [];
  private groups: VaultGroup[] = [];
  private addWho = "EVERYONE:";
  private addPreset: PresetId = "read";
  private busy = false;
  private message = "";
  private error = "";
  private bodyEl!: HTMLElement;

  constructor(
    app: App,
    private readonly api: NoteApiClient,
    private readonly vaultId: string,
    private readonly targets: ShareTarget[],
    /** Nach jeder Aenderung - main.ts aktualisiert Kennzeichen und Schreibschutz. */
    private readonly onChanged: () => void,
  ) {
    super(app);
  }

  onOpen(): void {
    const single = this.targets.length === 1 ? this.targets[0] : null;
    this.setTitle(single ? `Freigabe: ${single.path === "" ? "ganzer Vault" : single.path}` : `Freigabe: ${this.targets.length} Einträge`);
    this.contentEl.addClass("stoneintelligence-share");
    this.bodyEl = this.contentEl.createDiv();
    void this.load();
  }

  onClose(): void {
    this.contentEl.empty();
  }

  private get single(): ShareTarget | null {
    return this.targets.length === 1 ? this.targets[0] : null;
  }

  private async load(): Promise<void> {
    try {
      const [members, groups] = await Promise.all([this.api.listMembers(this.vaultId), this.api.listGroups(this.vaultId)]);
      this.members = members;
      this.groups = groups;
      this.report = this.single ? await this.reportFor(this.single) : null;
    } catch (error) {
      this.error = describe(error);
    }
    this.render();
  }

  private reportFor(target: ShareTarget): Promise<AccessReport> {
    return target.kind === "entry"
      ? this.api.noteAccess(this.vaultId, target.noteId)
      : this.api.folderAccess(this.vaultId, target.path);
  }

  private async apply(who: Who, preset: PresetId): Promise<void> {
    const change: GrantChange = { scopeType: who.scopeType, subject: who.subject, permissions: permissionsFor(preset) };
    await this.run(async () => {
      for (const target of this.targets) {
        if (target.kind === "entry") {
          await this.api.putNoteGrant(this.vaultId, target.noteId, change);
        } else {
          await this.api.putFolderGrant(this.vaultId, target.path, change);
        }
      }
      return `${who.label}: ${PRESETS.find((p) => p.id === preset)?.label ?? preset}.`;
    });
  }

  private async remove(who: Who): Promise<void> {
    await this.run(async () => {
      for (const target of this.targets) {
        if (target.kind === "entry") {
          await this.api.removeNoteGrant(this.vaultId, target.noteId, who.scopeType, who.subject);
        } else {
          await this.api.removeFolderGrant(this.vaultId, target.path, who.scopeType, who.subject);
        }
      }
      return `${who.label}: wieder wie darüber festgelegt.`;
    });
  }

  private async run(action: () => Promise<string>): Promise<void> {
    if (this.busy) {
      return;
    }
    this.busy = true;
    this.error = "";
    this.message = "";
    this.render();
    try {
      this.message = await action();
      if (this.single) {
        this.report = await this.reportFor(this.single);
      }
      this.onChanged();
    } catch (error) {
      this.error = describe(error);
    } finally {
      this.busy = false;
      this.render();
    }
  }

  private render(): void {
    const root = this.bodyEl;
    if (!root) {
      return;
    }
    root.empty();
    const report = this.report;
    if (report) {
      new Setting(root).setName("Deine Rechte hier")
        .setDesc(`${permissionsLabel(report.mine.permissions)} · ${describeSource(report.mine.source)}`);
    }
    const mayManage = this.single ? report?.mine.permissions.includes("MANAGE") ?? false : true;
    if (!mayManage) {
      if (report) {
        root.createEl("p", { cls: "setting-item-description", text: "Freigaben ändern kann, wer diese Stelle verwaltet." });
      }
      this.renderStatus(root);
      return;
    }
    if (report) {
      this.renderMembers(root, report);
      this.renderOtherGrants(root, report);
    }
    this.renderAdd(root);
    this.renderStatus(root);
  }

  private renderMembers(root: HTMLElement, report: AccessReport): void {
    root.createEl("h4", { text: "Wer hat Zugriff" });
    for (const member of report.members) {
      const own = report.grants.find((grant) => grant.scopeType === "USER" && grant.subject === member.subject);
      const who: Who = { scopeType: "USER", subject: member.subject, label: member.subject };
      const row = new Setting(root)
        .setName(member.subject)
        .setDesc(`${permissionsLabel(member.permissions)} · ${describeSource(member.source)}${member.groups.length ? ` · ${member.groups.join(", ")}` : ""}`);
      row.addDropdown((dropdown) => {
        dropdown.addOption("", own ? currentLabel(own) : "Ändern…");
        for (const preset of PRESETS) {
          dropdown.addOption(preset.id, preset.label);
        }
        dropdown.setValue("").setDisabled(this.busy).onChange((value) => {
          if (value) {
            void this.apply(who, value as PresetId);
          }
        });
      });
      if (own) {
        row.addExtraButton((button) => button.setIcon("rotate-ccw").setTooltip("Freigabe entfernen").setDisabled(this.busy)
          .onClick(() => void this.remove(who)));
      }
    }
  }

  private renderOtherGrants(root: HTMLElement, report: AccessReport): void {
    const others = report.grants.filter((grant) => grant.scopeType !== "USER");
    if (others.length > 0) {
      root.createEl("h4", { text: "Freigaben hier" });
      for (const grant of others) {
        new Setting(root).setName(describeScope(grant)).setDesc(permissionsLabel(grant.permissions))
          .addExtraButton((button) => button.setIcon("trash").setTooltip("Freigabe entfernen").setDisabled(this.busy)
            .onClick(() => void this.remove({ scopeType: grant.scopeType, subject: grant.subject, label: describeScope(grant) })));
      }
    }
    if (report.inherited.length > 0) {
      root.createEl("h4", { text: "Von weiter oben" });
      for (const grant of report.inherited) {
        new Setting(root).setName(describeScope(grant)).setDesc(`${permissionsLabel(grant.permissions)} · ${describeSource(grant)}`);
      }
    }
  }

  private renderAdd(root: HTMLElement): void {
    root.createEl("h4", { text: this.single ? "Gruppe oder alle freigeben" : "Für alle ausgewählten Einträge" });
    const options = this.whoOptions();
    new Setting(root).setName("Für").addDropdown((dropdown) => {
      for (const [key, who] of options) {
        dropdown.addOption(key, who.label);
      }
      dropdown.setValue(this.addWho).onChange((value) => (this.addWho = value));
    });
    new Setting(root).setName("Rechte")
      .addDropdown((dropdown) => {
        for (const preset of PRESETS) {
          dropdown.addOption(preset.id, preset.label);
        }
        dropdown.setValue(this.addPreset).onChange((value) => (this.addPreset = value as PresetId));
      })
      .addButton((button) => button.setButtonText("Übernehmen").setCta().setDisabled(this.busy).onClick(() => {
        const who = options.get(this.addWho);
        if (who) {
          void this.apply(who, this.addPreset);
        }
      }))
      .addButton((button) => button.setButtonText("Entfernen").setDisabled(this.busy || this.single !== null).onClick(() => {
        const who = options.get(this.addWho);
        if (who) {
          void this.remove(who);
        }
      }));
  }

  /** Einzeln: Gruppen und "alle" (Personen stehen schon in der Liste). Mehrere: auch Personen. */
  private whoOptions(): Map<string, Who> {
    const options = new Map<string, Who>([["EVERYONE:", { scopeType: "EVERYONE", subject: null, label: "Alle Mitglieder" }]]);
    for (const group of this.groups) {
      options.set(`GROUP:${group.id}`, { scopeType: "GROUP", subject: group.id, label: `Gruppe „${group.name}“` });
    }
    if (!this.single) {
      for (const member of this.members) {
        options.set(`USER:${member.subject}`, { scopeType: "USER", subject: member.subject, label: member.subject });
      }
    }
    return options;
  }

  private renderStatus(root: HTMLElement): void {
    if (this.message) {
      root.createEl("p", { cls: "stoneintelligence-invite-message", text: this.message });
    }
    if (this.error) {
      root.createEl("p", { cls: "stoneintelligence-invite-error", text: this.error });
    }
  }
}

function currentLabel(grant: Grant): string {
  const preset = presetOf(grant.permissions);
  return preset === "custom" ? `Eigene Auswahl: ${permissionsLabel(grant.permissions)}` : `Hier: ${PRESETS.find((p) => p.id === preset)?.label}`;
}

function describe(error: unknown): string {
  if (error instanceof HttpError) {
    return explainAccessError(error.status);
  }
  const message = (error as Error).message ?? String(error);
  new Notice(`StoneIntelligence: ${message}`);
  return message;
}
