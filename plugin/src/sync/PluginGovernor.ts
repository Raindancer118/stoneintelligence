import {
  disabledNotice, type EnabledPlugin, isEnforced, isPermitted, type PluginPolicy, pluginLabel, refusalNotice, violations,
} from "./pluginPolicy";

/**
 * Das, worauf wir uns bei `app.plugins` stützen. Obsidian dokumentiert es nicht; jedes Feld darf fehlen
 * (andere Version), dann tut der jeweilige Teil nichts, statt zu scheitern (ADR 0019, Prüfung gegen 1.15.0).
 */
export interface ObsidianPlugins {
  plugins?: Record<string, unknown>;
  manifests?: Record<string, { id: string; name?: string; version?: string }>;
  isEnabled?(): boolean;
  disablePluginAndSave?(id: string): Promise<void>;
  enablePluginAndSave?(id: string): Promise<boolean>;
  installPlugin?(repo: string, version: string, manifest: { id: string; name?: string }): Promise<boolean>;
}

export type GuardState = "inactive" | "active" | "unavailable";

/**
 * Setzt die Plugin-Richtlinie des Vaults im Plugin durch (ADR 0019): deaktiviert nicht erlaubte Plugins und
 * weist Installation und Aktivierung zurück. Eine Leitplanke, kein Schloss: wer Dateien oder Obsidian selbst
 * kontrolliert, umgeht sie (Details im ADR).
 */
export class PluginGovernor {
  private policy: PluginPolicy | null = null;
  private restorers: (() => void)[] = [];
  private state: GuardState = "inactive";

  constructor(
    private readonly getPlugins: () => ObsidianPlugins | undefined,
    private readonly notify: (message: string) => void,
    private readonly log: (message: string, error?: unknown) => void,
  ) {}

  guardState(): GuardState {
    return this.state;
  }

  currentPolicy(): PluginPolicy | null {
    return this.policy;
  }

  /** Aktivierte Community-Plugins, wie der Server sie in der Geräte-Meldung erwartet. */
  enabled(): EnabledPlugin[] {
    const host = this.getPlugins();
    return Object.keys(host?.plugins ?? {}).map((id) => ({ id, version: host?.manifests?.[id]?.version ?? "unbekannt" }));
  }

  /** Eingeschränkter Modus: keine Community-Plugins aktiv. Obsidian speichert das je Vault im Profil, nicht im Vault. */
  restrictedMode(): boolean {
    const isEnabled = this.getPlugins()?.isEnabled;
    return typeof isEnabled === "function" ? !isEnabled.call(this.getPlugins()) : false;
  }

  setPolicy(policy: PluginPolicy | null): void {
    this.policy = policy;
    this.removeGuards();
    this.state = "inactive";
    if (isEnforced(policy)) {
      this.installGuards();
    }
  }

  /** Deaktiviert alle nicht erlaubten Plugins und liefert deren Bezeichnungen. */
  async enforce(): Promise<string[]> {
    const host = this.getPlugins();
    const offending = violations(this.policy, this.enabled());
    if (!host || offending.length === 0) {
      return [];
    }
    if (typeof host.disablePluginAndSave !== "function") {
      this.log("StoneIntelligence: Plugins lassen sich in dieser Obsidian-Version nicht deaktivieren");
      return [];
    }
    const disabled: string[] = [];
    for (const plugin of offending) {
      try {
        // Schreibgeschützte Dateien (MDM): das Entladen gelingt, nur das Speichern scheitert still; beim
        // nächsten Start wird erneut deaktiviert.
        await host.disablePluginAndSave(plugin.id);
        disabled.push(pluginLabel(plugin));
      } catch (error) {
        this.log(`StoneIntelligence: Plugin ${plugin.id} ließ sich nicht deaktivieren`, error);
      }
    }
    if (disabled.length > 0) {
      this.notify(disabledNotice(disabled));
    }
    return disabled;
  }

  dispose(): void {
    this.removeGuards();
    this.state = "inactive";
  }

  private installGuards(): void {
    const host = this.getPlugins();
    if (!host || (typeof host.installPlugin !== "function" && typeof host.enablePluginAndSave !== "function")) {
      this.state = "unavailable";
      return;
    }
    if (typeof host.installPlugin === "function") {
      const original = host.installPlugin;
      const wrapper: NonNullable<ObsidianPlugins["installPlugin"]> = (repo, version, manifest) => {
        if (!manifest?.id || !this.permits({ id: manifest.id, version })) {
          this.notify(refusalNotice(manifest?.name ?? manifest?.id ?? repo));
          return Promise.resolve(false);
        }
        return original.call(host, repo, version, manifest);
      };
      host.installPlugin = wrapper;
      this.restorers.push(() => {
        if (host.installPlugin === wrapper) {
          host.installPlugin = original;
        }
      });
    }
    if (typeof host.enablePluginAndSave === "function") {
      const original = host.enablePluginAndSave;
      const wrapper: NonNullable<ObsidianPlugins["enablePluginAndSave"]> = (id) => {
        const manifest = host.manifests?.[id];
        if (!this.permits({ id, version: manifest?.version })) {
          this.notify(refusalNotice(manifest?.name ?? id));
          return Promise.resolve(false);
        }
        return original.call(host, id);
      };
      host.enablePluginAndSave = wrapper;
      this.restorers.push(() => {
        if (host.enablePluginAndSave === wrapper) {
          host.enablePluginAndSave = original;
        }
      });
    }
    this.state = "active";
  }

  private removeGuards(): void {
    for (const restore of this.restorers.splice(0)) {
      restore();
    }
  }

  private permits(plugin: { id: string; version?: string }): boolean {
    return !isEnforced(this.policy) || isPermitted(this.policy, plugin);
  }
}
