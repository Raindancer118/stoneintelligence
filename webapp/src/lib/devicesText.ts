import type { Device, PluginRule } from "./api";

const ID = /^[A-Za-z0-9._-]{1,100}$/;
const VERSION = /^[A-Za-z0-9._+-]{1,50}$/;

/** Eine Zeile je Plugin: `kennung` (jede Version) oder `kennung@1.2.3` (nur diese Version). */
export function parseAllowedLines(text: string): { rules: PluginRule[]; error: string | null } {
  const rules: PluginRule[] = [];
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) continue;
    const [id, version, ...rest] = line.split("@");
    if (rest.length > 0 || !ID.test(id) || (version !== undefined && !VERSION.test(version))) {
      return { rules: [], error: `„${line}“ ist keine gültige Angabe. Schreibe die Plugin-Kennung, optional mit @Version.` };
    }
    if (rules.some(rule => rule.id === id)) return { rules: [], error: `„${id}“ steht mehrfach in der Liste.` };
    rules.push({ id, version: version ?? null });
  }
  return { rules, error: null };
}

export function formatAllowedLines(rules: PluginRule[]): string {
  return rules.map(rule => (rule.version ? `${rule.id}@${rule.version}` : rule.id)).join("\n");
}

export function deviceStatusText(device: Pick<Device, "status" | "offending" | "wipedAt">): string {
  switch (device.status) {
    case "COMPLIANT": return "Entspricht der Richtlinie";
    case "NON_COMPLIANT": return `Verstößt gegen die Richtlinie: ${device.offending.join(", ")}`;
    case "REVOKED": return device.wipedAt ? "Gesperrt, Daten gelöscht" : "Gesperrt";
    case "WIPE_PENDING": return "Daten werden beim nächsten Verbinden gelöscht";
    case "UNKNOWN": return "Nicht gemeldet";
  }
}

/** Für die Farbe des Status: nur Bedeutung, kein Text. */
export function deviceTone(device: Pick<Device, "status">): "ok" | "warn" | "bad" {
  return device.status === "COMPLIANT" ? "ok" : device.status === "NON_COMPLIANT" ? "warn" : "bad";
}
