import type { DeviceView } from "./NoteApiClient";
import { formatDate } from "./historyText";
import type { PolicyRule } from "./pluginPolicy";

const ID = /^[A-Za-z0-9._-]{1,100}$/;
const VERSION = /^[A-Za-z0-9._+-]{1,50}$/;

/** Eine Zeile je Plugin: `kennung` (jede Version) oder `kennung@1.2.3` (nur diese Version). */
export function parseAllowedLines(text: string): { rules: PolicyRule[]; error: string | null } {
  const rules: PolicyRule[] = [];
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) {
      continue;
    }
    const [id, version, ...rest] = line.split("@");
    if (rest.length > 0 || !ID.test(id) || (version !== undefined && !VERSION.test(version))) {
      return { rules: [], error: `„${line}“ ist keine gültige Angabe. Schreibe die Plugin-Kennung, optional mit @Version.` };
    }
    if (rules.some((rule) => rule.id === id)) {
      return { rules: [], error: `„${id}“ steht mehrfach in der Liste.` };
    }
    rules.push({ id, version: version ?? null });
  }
  return { rules, error: null };
}

export function formatAllowedLines(rules: PolicyRule[]): string {
  return rules.map((rule) => (rule.version ? `${rule.id}@${rule.version}` : rule.id)).join("\n");
}

const STATUS: Record<DeviceView["status"], string> = {
  COMPLIANT: "Entspricht der Richtlinie",
  NON_COMPLIANT: "Verstößt gegen die Richtlinie",
  REVOKED: "Gesperrt",
  WIPE_PENDING: "Wird gelöscht",
  UNKNOWN: "Nicht gemeldet",
};

export function deviceStatusText(device: Pick<DeviceView, "status" | "offending" | "wipedAt">): string {
  if (device.status === "NON_COMPLIANT") {
    return `${STATUS.NON_COMPLIANT}: ${device.offending.join(", ")}`;
  }
  if (device.status === "REVOKED" && device.wipedAt) {
    return "Gesperrt, Daten gelöscht";
  }
  return STATUS[device.status];
}

export function deviceLine(device: DeviceView): string {
  return `${device.person} · ${device.platform} · Obsidian ${device.obsidianVersion} · zuletzt ${formatDate(device.lastSeen)}`;
}
