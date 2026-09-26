import { ItemView, Notice, Setting, type WorkspaceLeaf } from "obsidian";
import { explainAccessError, type Permission, permissionsLabel } from "../sync/accessPlan";
import { canCancel, jobStatusText } from "../sync/aiJobText";
import { describeEvent, formatDate } from "../sync/historyText";
import {
  type AiJob, type HistoryEvent, HttpError, type LinkingSettings, type NoteApiClient, type PendingInvitation, type VaultGroup, type VaultMember, type VaultRole,
} from "../sync/NoteApiClient";
import { confirmAction } from "./ConfirmModal";

export const VIEW_TYPE_VAULT_ADMIN = "stoneintelligence-vault-admin";

export interface VaultAdminHost {
  api(): NoteApiClient;
  currentVaultId(): string | null;
  vaultName(): string | null;
  accountName(): string | null;
  openInvite(): void;
  vaultRenamed(name: string): void;
}

export type Tab = "members" | "groups" | "roles" | "invitations" | "ai" | "log";
const TABS: { id: Tab; label: string }[] = [
  { id: "members", label: "Mitglieder" },
  { id: "groups", label: "Gruppen" },
  { id: "roles", label: "Rollen" },
  { id: "invitations", label: "Einladungen" },
  { id: "ai", label: "KI" },
  { id: "log", label: "Protokoll" },
];
const ALL: Permission[] = ["READ", "WRITE", "CREATE", "DELETE", "MANAGE"];
const WORD: Record<Permission, string> = { READ: "Lesen", WRITE: "Bearbeiten", CREATE: "Anlegen", DELETE: "Löschen", MANAGE: "Verwalten" };

/**
 * "Vault-Verwaltung" in Obsidian (ADR 0011): Mitglieder, Gruppen, Rollen, Einladungen und das
 * Protokoll - dasselbe wie im Web-Dashboard. Wer nicht verwaltet, sieht alles nur lesend und kann
 * den Vault verlassen.
 */
export class VaultAdminView extends ItemView {
  private tab: Tab = "members";
  private members: VaultMember[] = [];
  private groups: VaultGroup[] = [];
  private roles: VaultRole[] = [];
  private invitations: PendingInvitation[] = [];
  private log: HistoryEvent[] = [];
  private jobs: AiJob[] = [];
  private linking: LinkingSettings | null = null;
  private canWrite = false;
  private jobTimer: number | null = null;
  private manage = false;
  private busy = false;
  private error = "";
  private message = "";

  constructor(leaf: WorkspaceLeaf, private readonly host: VaultAdminHost) {
    super(leaf);
  }

  getViewType(): string {
    return VIEW_TYPE_VAULT_ADMIN;
  }

  getDisplayText(): string {
    return "Vault-Verwaltung";
  }

  getIcon(): string {
    return "users";
  }

  async onOpen(): Promise<void> {
    this.contentEl.addClass("stoneintelligence-admin");
    await this.reload();
  }

  async onClose(): Promise<void> {
    window.clearTimeout(this.jobTimer ?? undefined);
    this.contentEl.empty();
  }

  /** Von aussen, z. B. nach "Mit KI einlesen". */
  async showTab(tab: Tab): Promise<void> {
    this.tab = tab;
    await this.reload();
  }

  /** Von aussen, z. B. nach einer Rechte-Ankuendigung. */
  async reload(): Promise<void> {
    const vaultId = this.host.currentVaultId();
    if (!vaultId) {
      this.error = "Dieser Obsidian-Vault ist mit keinem StoneIntelligence-Vault verbunden.";
      this.render();
      return;
    }
    try {
      const api = this.host.api();
      const [members, groups, roles, permissions] = await Promise.all([
        api.listMembers(vaultId), api.listGroups(vaultId), api.listRoles(vaultId), api.permissions(vaultId),
      ]);
      this.members = members;
      this.groups = groups;
      this.roles = roles;
      this.manage = permissions.includes("MANAGE");
      this.canWrite = permissions.includes("WRITE");
      this.invitations = this.manage ? await api.listInvitations(vaultId) : [];
      this.log = await api.vaultLog(vaultId, "", 100);
      this.jobs = await api.listAiJobs(vaultId).catch(() => []);
      // Aeltere Server kennen die Verlinkung noch nicht - dann entfaellt der Abschnitt.
      this.linking = await api.linkingSettings(vaultId).catch(() => null);
      this.error = "";
    } catch (error) {
      this.error = describe(error);
    }
    this.render();
  }

  private async run(action: (api: NoteApiClient, vaultId: string) => Promise<unknown>, done: string): Promise<void> {
    const vaultId = this.host.currentVaultId();
    if (this.busy || !vaultId) {
      return;
    }
    this.busy = true;
    this.error = "";
    this.message = "";
    this.render();
    try {
      await action(this.host.api(), vaultId);
      this.message = done;
    } catch (error) {
      this.error = describe(error);
    } finally {
      this.busy = false;
    }
    await this.reload();
  }

  private render(): void {
    const root = this.contentEl;
    root.empty();
    root.createEl("h3", { text: this.host.vaultName() ?? "Vault" });
    if (this.manage) {
      let name = this.host.vaultName() ?? "";
      new Setting(root).setName("Name des Vaults")
        .addText((text) => text.setValue(name).onChange((value) => (name = value)))
        .addButton((button) => button.setButtonText("Umbenennen").setDisabled(this.busy).onClick(() => {
          if (name.trim()) {
            void this.run(async (api, vaultId) => {
              const renamed = await api.renameVault(vaultId, name.trim());
              this.host.vaultRenamed(renamed.name);
            }, `Der Vault heißt jetzt „${name.trim()}“.`);
          }
        }));
    }
    const tabs = root.createDiv({ cls: "stoneintelligence-admin-tabs" });
    for (const tab of TABS) {
      if (tab.id === "invitations" && !this.manage) {
        continue;
      }
      const button = tabs.createEl("button", { text: tab.label, cls: tab.id === this.tab ? "mod-cta" : "" });
      button.onclick = () => {
        this.tab = tab.id;
        this.render();
      };
    }
    if (this.message) {
      root.createEl("p", { cls: "stoneintelligence-invite-message", text: this.message });
    }
    if (this.error) {
      root.createEl("p", { cls: "stoneintelligence-invite-error", text: this.error });
    }
    const body = root.createDiv();
    switch (this.tab) {
      case "members":
        this.renderMembers(body);
        break;
      case "groups":
        this.renderGroups(body);
        break;
      case "roles":
        this.renderRoles(body);
        break;
      case "invitations":
        this.renderInvitations(body);
        break;
      case "ai":
        this.renderJobs(body);
        break;
      case "log":
        this.renderLog(body);
        break;
    }
    this.scheduleJobRefresh();
  }

  private renderMembers(root: HTMLElement): void {
    if (this.manage) {
      new Setting(root).setName("Jemanden dazuholen")
        .addButton((button) => button.setButtonText("Mitbearbeiter einladen").setCta().onClick(() => this.host.openInvite()));
    }
    const me = this.host.accountName();
    for (const member of this.members) {
      const row = new Setting(root).setName(member.subject === me ? `${member.subject} (du)` : member.subject)
        .setDesc(`${permissionsLabel(member.permissions)}${member.groups.length ? ` · ${member.groups.map((g) => g.name).join(", ")}` : ""}`);
      if (member.subject === me) {
        row.addButton((button) => button.setButtonText("Vault verlassen").setWarning().setDisabled(this.busy).onClick(async () => {
          if (await confirmAction(this.app, "Vault verlassen?", "Du verlierst den Zugriff auf alle Notizen dieses Vaults, bis dich jemand wieder einlädt.", "Verlassen")) {
            await this.run((api, vaultId) => api.removeFromVault(vaultId, member.subject), "Du hast den Vault verlassen.");
          }
        }));
      } else if (this.manage) {
        row.addButton((button) => button.setButtonText("Entfernen").setDisabled(this.busy).onClick(async () => {
          if (await confirmAction(this.app, `${member.subject} entfernen?`, `${member.subject} verliert alle Gruppen und persönlichen Freigaben in diesem Vault.`, "Entfernen")) {
            await this.run((api, vaultId) => api.removeFromVault(vaultId, member.subject), `${member.subject} ist nicht mehr im Vault.`);
          }
        }));
      }
    }
  }

  private renderGroups(root: HTMLElement): void {
    if (this.manage) {
      let name = "";
      new Setting(root).setName("Neue Gruppe")
        .addText((text) => text.setPlaceholder("z. B. Lektorat").onChange((value) => (name = value)))
        .addButton((button) => button.setButtonText("Anlegen").setDisabled(this.busy).onClick(() => {
          if (name.trim()) {
            void this.run((api, vaultId) => api.createGroup(vaultId, name.trim()), `Gruppe „${name.trim()}“ angelegt.`);
          }
        }));
    }
    for (const group of this.groups) {
      root.createEl("h4", { text: group.name });
      const roleNames = this.roles.filter((role) => group.roleIds.includes(role.id)).map((role) => role.name);
      new Setting(root).setName("Rollen").setDesc(roleNames.length ? roleNames.join(", ") : "keine");
      if (!this.manage) {
        new Setting(root).setName("Mitglieder").setDesc(group.memberSubjects.join(", ") || "niemand");
        continue;
      }
      for (const role of this.roles) {
        new Setting(root).setName(`Rolle „${role.name}“`).setDesc(permissionsLabel(role.permissions))
          .addToggle((toggle) => toggle.setValue(group.roleIds.includes(role.id)).setDisabled(this.busy).onChange((on) => {
            void this.run((api, vaultId) => on ? api.assignRole(vaultId, group.id, role.id) : api.unassignRole(vaultId, group.id, role.id),
              on ? `„${group.name}“ hat jetzt die Rolle „${role.name}“.` : `„${group.name}“ hat die Rolle „${role.name}“ nicht mehr.`);
          }));
      }
      for (const subject of group.memberSubjects) {
        new Setting(root).setName(subject).setDesc("Mitglied")
          .addExtraButton((button) => button.setIcon("x").setTooltip("Aus der Gruppe nehmen").setDisabled(this.busy)
            .onClick(() => void this.run((api, vaultId) => api.removeGroupMember(vaultId, group.id, subject), `${subject} ist nicht mehr in „${group.name}“.`)));
      }
      const candidates = this.members.filter((member) => !group.memberSubjects.includes(member.subject));
      let chosen = candidates[0]?.subject ?? "";
      let newName = group.name;
      const actions = new Setting(root);
      if (candidates.length > 0) {
        actions.addDropdown((dropdown) => {
          for (const member of candidates) {
            dropdown.addOption(member.subject, member.subject);
          }
          dropdown.onChange((value) => (chosen = value));
        }).addButton((button) => button.setButtonText("Hinzufügen").setDisabled(this.busy)
          .onClick(() => void this.run((api, vaultId) => api.addGroupMember(vaultId, group.id, chosen), `${chosen} ist jetzt in „${group.name}“.`)));
      }
      new Setting(root).setName("Gruppe umbenennen oder löschen")
        .addText((text) => text.setValue(group.name).onChange((value) => (newName = value)))
        .addButton((button) => button.setButtonText("Umbenennen").setDisabled(this.busy).onClick(() => {
          if (newName.trim() && newName.trim() !== group.name) {
            void this.run((api, vaultId) => api.renameGroup(vaultId, group.id, newName.trim()), `Gruppe heißt jetzt „${newName.trim()}“.`);
          }
        }))
        .addButton((button) => button.setButtonText("Löschen").setWarning().setDisabled(this.busy).onClick(async () => {
          if (await confirmAction(this.app, `Gruppe „${group.name}“ löschen?`, "Ihre Mitglieder verlieren die Rechte, die sie über diese Gruppe hatten, und ihre Freigaben fallen weg.", "Löschen")) {
            await this.run((api, vaultId) => api.deleteGroup(vaultId, group.id), `Gruppe „${group.name}“ gelöscht.`);
          }
        }));
    }
  }

  private renderRoles(root: HTMLElement): void {
    if (this.manage) {
      let name = "";
      const chosen = new Set<Permission>(["READ"]);
      const create = new Setting(root).setName("Neue Rolle").addText((text) => text.setPlaceholder("z. B. Redaktion").onChange((value) => (name = value)));
      for (const permission of ALL) {
        create.addToggle((toggle) => toggle.setTooltip(WORD[permission]).setValue(chosen.has(permission))
          .onChange((on) => (on ? chosen.add(permission) : chosen.delete(permission))));
      }
      create.addButton((button) => button.setButtonText("Anlegen").setDisabled(this.busy).onClick(() => {
        if (name.trim()) {
          void this.run((api, vaultId) => api.createRole(vaultId, name.trim(), [...chosen]), `Rolle „${name.trim()}“ angelegt.`);
        }
      }));
      root.createEl("p", { cls: "setting-item-description", text: `Schalter in dieser Reihenfolge: ${ALL.map((p) => WORD[p]).join(", ")}.` });
    }
    for (const role of this.roles) {
      const row = new Setting(root).setName(role.name).setDesc(permissionsLabel(role.permissions));
      if (!this.manage) {
        continue;
      }
      for (const permission of ALL) {
        row.addToggle((toggle) => toggle.setTooltip(WORD[permission]).setValue(role.permissions.includes(permission)).setDisabled(this.busy)
          .onChange((on) => {
            const next = on ? [...role.permissions, permission] : role.permissions.filter((p) => p !== permission);
            void this.run((api, vaultId) => api.updateRole(vaultId, role.id, null, next), `Rolle „${role.name}“: ${permissionsLabel(next)}.`);
          }));
      }
      row.addExtraButton((button) => button.setIcon("trash").setTooltip("Rolle löschen").setDisabled(this.busy).onClick(async () => {
        if (await confirmAction(this.app, `Rolle „${role.name}“ löschen?`, "Gruppen mit dieser Rolle verlieren deren Rechte.", "Löschen")) {
          await this.run((api, vaultId) => api.deleteRole(vaultId, role.id), `Rolle „${role.name}“ gelöscht.`);
        }
      }));
    }
  }

  private renderInvitations(root: HTMLElement): void {
    new Setting(root).setName("Neue Einladung")
      .addButton((button) => button.setButtonText("Mitbearbeiter einladen").setCta().onClick(() => this.host.openInvite()));
    if (this.invitations.length === 0) {
      root.createEl("p", { cls: "setting-item-description", text: "Keine offenen Einladungen." });
    }
    for (const invitation of this.invitations) {
      new Setting(root).setName(invitation.email)
        .setDesc(`${invitation.access === "READ" ? "lesen" : "bearbeiten"} · gültig bis ${formatDate(invitation.expiresAt)}`)
        .addButton((button) => button.setButtonText("Zurückziehen").setDisabled(this.busy)
          .onClick(() => void this.run((api, vaultId) => api.revokeInvitation(vaultId, invitation.id), `Einladung an ${invitation.email} zurückgezogen.`)));
    }
  }

  /** ADR 0012: naechtliche Verlinkung - woertliche Nennungen von Titeln und Aliasen werden zu Links. */
  private renderLinking(root: HTMLElement): void {
    const linking = this.linking;
    if (!linking) {
      return;
    }
    root.createEl("h4", { text: "Verlinkung" });
    root.createEl("p", {
      cls: "setting-item-description",
      text: "Nennt eine Notiz den Titel oder einen Alias einer anderen, wird die Stelle zum Link – "
        + "nur eingefügtes Markup, der Text bleibt wie er ist. Das geschieht auf dem Server, ohne externe KI. "
        + "Jeder Lauf lässt sich unter „KI-Änderungen“ rückgängig machen.",
    });
    const save = (change: Partial<LinkingSettings>): void => {
      const next = { ...linking, ...change };
      void this.run((api, vaultId) => api.updateLinking(vaultId, {
        enabled: next.enabled, linkHumanNotes: next.linkHumanNotes, maxLinksPerNote: next.maxLinksPerNote, service: next.service,
        mode: next.mode ?? null,
      }), next.enabled ? "Die Verlinkung läuft jetzt jede Nacht um 2 Uhr." : "Die nächtliche Verlinkung ist aus.");
    };
    new Setting(root).setName("Was verlinkt wird")
      .setDesc(linking.mode === "AI"
        ? "Eine KI entscheidet, ob ähnliche Notizen wirklich zusammengehören, und wählt die Stelle. Dafür gehen nachts Auszüge an den KI-Anbieter – nur von Notizen, deren Verfasser zugestimmt haben (unten)."
        : "Ähnliche Inhalte kommen unter „Verwandt“ – berechnet auf dem Server, ohne externe KI.")
      .addDropdown((dropdown) => dropdown
        .addOption("LITERAL", "Nur wörtliche Nennungen")
        .addOption("SEMANTIC", "Auch ähnliche Inhalte")
        .addOption("AI", "Ähnliche Inhalte, von einer KI geprüft")
        .setValue(linking.mode ?? "LITERAL")
        .setDisabled(!this.manage || this.busy)
        .onChange((value) => save({ mode: value as LinkingSettings["mode"] })));
    if (linking.mode === "AI") {
      new Setting(root).setName("Meine Notizen dürfen zur KI-Prüfung")
        .setDesc(`Auszüge deiner Notizen gehen dann nachts an den KI-Anbieter (auch in die USA, s. Datenschutzerklärung). `
          + `Jederzeit widerrufbar. Bisher zugestimmt: ${linking.aiConsentCount ?? 0} Mitglied(er).`)
        .addToggle((toggle) => toggle.setValue(Boolean(linking.aiConsent)).setDisabled(this.busy)
          .onChange((on) => void this.run((api, vaultId) => api.setLinkingConsent(vaultId, on),
            on ? "Deine Notizen dürfen jetzt zur KI-Prüfung." : "Deine Notizen gehen nicht mehr zur KI-Prüfung.")));
    }
    new Setting(root).setName("Jede Nacht um 2 Uhr verlinken")
      .setDesc(linking.enabled && linking.requestedBy ? `Läuft mit den Rechten von ${linking.requestedBy}.` : "Aus.")
      .addToggle((toggle) => toggle.setValue(linking.enabled).setDisabled(!this.manage || this.busy)
        .onChange((on) => save({ enabled: on })));
    new Setting(root).setName("Auch in Notizen von Menschen")
      .setDesc("Aus: Links nur in Notizen, die die KI geschrieben hat.")
      .addToggle((toggle) => toggle.setValue(linking.linkHumanNotes).setDisabled(!this.manage || this.busy)
        .onChange((on) => save({ linkHumanNotes: on })));
    let max = linking.maxLinksPerNote === null ? "" : String(linking.maxLinksPerNote);
    new Setting(root).setName("Höchstens neue Links je Notiz und Lauf").setDesc("Leer lassen für unbegrenzt.")
      .addText((text) => text.setPlaceholder("unbegrenzt").setValue(max).setDisabled(!this.manage || this.busy)
        .onChange((value) => (max = value.trim())))
      .addButton((button) => button.setButtonText("Speichern").setDisabled(!this.manage || this.busy).onClick(() => {
        const parsed = max === "" ? null : Number.parseInt(max, 10);
        if (parsed !== null && (!Number.isFinite(parsed) || parsed < 1)) {
          this.error = "Bitte eine Zahl ab 1 eintragen oder das Feld leer lassen.";
          this.render();
          return;
        }
        save({ maxLinksPerNote: parsed });
      }));
    new Setting(root).setName("Jetzt verlinken")
      .setDesc(linking.lastRunAt ? `Zuletzt: ${formatDate(linking.lastRunAt)}` : "Noch nie gelaufen.")
      .addButton((button) => button.setButtonText("Jetzt verlinken").setDisabled(!this.canWrite || this.busy)
        .onClick(() => void this.run((api, vaultId) => api.runLinking(vaultId), "Die Verlinkung läuft – den Fortschritt siehst du unten.")));
  }

  private renderJobs(root: HTMLElement): void {
    this.renderLinking(root);
    root.createEl("h4", { text: "Aufträge" });
    root.createEl("p", {
      cls: "setting-item-description",
      text: "PDFs und Textdateien liest du per Rechtsklick → „Mit KI einlesen…“ ein. Was die KI geschrieben hat, lässt sich unter „KI-Änderungen“ rückgängig machen.",
    });
    if (this.jobs.length === 0) {
      root.createEl("p", { cls: "setting-item-description", text: "Noch keine KI-Aufträge." });
    }
    for (const job of this.jobs) {
      const row = new Setting(root).setName(job.fileName).setDesc(`${jobStatusText(job)} · ${job.requestedBy} · ${formatDate(job.createdAt)}`);
      if (canCancel(job)) {
        row.addButton((button) => button.setButtonText("Abbrechen").setDisabled(this.busy).onClick(async () => {
          const running = job.status === "RUNNING";
          if (!running || await confirmAction(this.app, "Laufendes Einlesen abbrechen?", "Was die KI schon geschrieben hat, wird rückgängig gemacht.", "Abbrechen")) {
            await this.run((api, vaultId) => api.cancelAiJob(vaultId, job.id), `„${job.fileName}“ abgebrochen.`);
          }
        }));
      }
    }
  }

  /** Solange etwas laeuft und der KI-Reiter offen ist, alle paar Sekunden den Fortschritt holen. */
  private scheduleJobRefresh(): void {
    window.clearTimeout(this.jobTimer ?? undefined);
    if (this.tab === "ai" && this.jobs.some(canCancel)) {
      this.jobTimer = window.setTimeout(() => void this.reload(), 5000);
    }
  }

  private renderLog(root: HTMLElement): void {
    if (this.log.length === 0) {
      root.createEl("p", { cls: "setting-item-description", text: "Noch nichts, was du sehen darfst." });
      return;
    }
    const list = root.createEl("ol", { cls: "stoneintelligence-history-events" });
    for (const event of this.log) {
      const item = list.createEl("li");
      item.createEl("span", { cls: "stoneintelligence-history-when", text: formatDate(event.occurredAt) });
      item.createEl("span", { text: describeEvent(event) });
    }
  }
}

function describe(error: unknown): string {
  if (error instanceof HttpError) {
    return explainAccessError(error.status);
  }
  const message = (error as Error).message ?? String(error);
  new Notice(`StoneIntelligence: ${message}`);
  return message;
}
