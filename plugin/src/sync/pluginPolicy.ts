/** Plugin-Richtlinie eines Vaults (ADR 0019) - dieselben Regeln wie `PluginPolicy` auf dem Server. */
export type PolicyMode = "OFF" | "ENFORCE";

export interface PolicyRule {
  id: string;
  /** Leer: jede Version. */
  version?: string | null;
}

export interface PluginPolicy {
  mode: PolicyMode;
  allowed: PolicyRule[];
  /** Immer erlaubt, auch ohne Eintrag: das StoneIntelligence-Plugin selbst. */
  alwaysAllowed?: string;
}

export interface EnabledPlugin {
  id: string;
  version: string;
}

export const OWN_PLUGIN_ID = "stoneintelligence";

export function isEnforced(policy: PluginPolicy | null | undefined): policy is PluginPolicy {
  return policy?.mode === "ENFORCE";
}

export function isPermitted(policy: PluginPolicy, plugin: { id: string; version?: string }): boolean {
  if (plugin.id === OWN_PLUGIN_ID || plugin.id === policy.alwaysAllowed) {
    return true;
  }
  return policy.allowed.some((rule) => rule.id === plugin.id && (!rule.version || rule.version === plugin.version));
}

/** Alle aktivierten Plugins, die die Richtlinie nicht erlaubt; ohne Durchsetzung keine. */
export function violations(policy: PluginPolicy | null | undefined, enabled: EnabledPlugin[]): EnabledPlugin[] {
  if (!isEnforced(policy)) {
    return [];
  }
  return enabled.filter((plugin) => !isPermitted(policy, plugin));
}

export function pluginLabel(plugin: { id: string; version?: string | null }): string {
  return plugin.version ? `${plugin.id} ${plugin.version}` : plugin.id;
}

export function refusalNotice(name: string): string {
  return `StoneIntelligence: Das Plugin „${name}“ ist für diesen Vault nicht freigegeben. Die Richtlinie deines Unternehmens erlaubt nur ausgewählte Plugins.`;
}

export function disabledNotice(labels: string[]): string {
  return labels.length === 1
    ? `StoneIntelligence: Das Plugin „${labels[0]}“ wurde deaktiviert, weil es die Plugin-Richtlinie dieses Vaults nicht erlaubt.`
    : `StoneIntelligence: ${labels.length} Plugins wurden deaktiviert, weil die Plugin-Richtlinie dieses Vaults sie nicht erlaubt: ${labels.join(", ")}.`;
}

/** Gespeicherte oder vom Server gelieferte Daten in eine brauchbare Richtlinie umwandeln. */
export function parsePolicy(raw: unknown): PluginPolicy | null {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const value = raw as { mode?: unknown; allowed?: unknown; alwaysAllowed?: unknown };
  if (value.mode !== "OFF" && value.mode !== "ENFORCE") {
    return null;
  }
  const allowed = Array.isArray(value.allowed)
    ? value.allowed.flatMap((rule): PolicyRule[] => {
      const entry = rule as { id?: unknown; version?: unknown };
      return typeof entry?.id === "string" && entry.id
        ? [{ id: entry.id, version: typeof entry.version === "string" && entry.version ? entry.version : null }]
        : [];
    })
    : [];
  return { mode: value.mode, allowed, alwaysAllowed: typeof value.alwaysAllowed === "string" ? value.alwaysAllowed : OWN_PLUGIN_ID };
}
