import { formatRelativeTime, type SyncActivity } from "../sync/SyncActivity";

export interface StatusPresentation {
  /** Lucide-Iconname (Obsidians `setIcon`). */
  icon: string;
  label: string;
  tooltip: string;
  tone: "ok" | "muted" | "warning" | "error";
  spinning: boolean;
  /** Was ein Klick auf die Statusanzeige sinnvollerweise tun soll. */
  action: "settings" | "login" | "panel";
}

/**
 * EINE Stelle, die aus dem Sync-Zustand Text/Icon macht - Statusleiste, Seitenleiste und
 * Einstellungen sagen dadurch dasselbe mit denselben Worten.
 */
export function presentStatus(
  activity: SyncActivity,
  counts: { pending: number },
  now: number = Date.now(),
): StatusPresentation {
  const last = activity.lastSyncedAt();
  const lastText = last ? `Zuletzt synchronisiert ${formatRelativeTime(last, now)}.` : "Noch nicht synchronisiert.";
  const pendingText = counts.pending > 0 ? ` · ${counts.pending} ausstehend` : "";

  switch (activity.phase()) {
    case "notConfigured":
      return {
        icon: "settings", label: "Sync einrichten", tone: "muted", spinning: false, action: "settings",
        tooltip: "StoneIntelligence ist noch nicht eingerichtet. Klicken, um Anmeldung und Vault zu wählen.",
      };
    case "signedOut":
      return {
        icon: "log-in", label: "Nicht angemeldet", tone: "warning", spinning: false, action: "login",
        tooltip: "Klicken, um dich anzumelden. Solange bleibt der Sync angehalten.",
      };
    case "paused":
      return {
        icon: "pause-circle", label: `Pausiert${pendingText}`, tone: "muted", spinning: false, action: "panel",
        tooltip: `Sync ist pausiert. ${lastText}`,
      };
    case "syncing": {
      const progress = activity.progress();
      const label = progress && progress.total > 0 ? `Synchronisiere ${progress.done}/${progress.total}` : "Synchronisiere…";
      return { icon: "refresh-cw", label, tone: "ok", spinning: true, action: "panel", tooltip: `${label}. ${lastText}` };
    }
    case "connecting":
      return {
        icon: "refresh-cw", label: `Verbinde…${pendingText}`, tone: "muted", spinning: true, action: "panel",
        tooltip: `Verbindung zum Server wird aufgebaut. ${lastText}`,
      };
    case "offline":
      return {
        icon: "cloud-off", label: `Offline${pendingText}`, tone: "muted", spinning: false, action: "panel",
        tooltip: `Keine Verbindung zum Server. Änderungen werden lokal gesammelt und nachgereicht. ${lastText}`,
      };
    case "attention": {
      const count = activity.problems().length;
      return {
        icon: "alert-triangle", label: count === 1 ? "1 Problem" : `${count} Probleme`, tone: "warning",
        spinning: false, action: "panel", tooltip: `Einige Notizen brauchen Aufmerksamkeit. ${lastText}`,
      };
    }
    case "synced":
    default:
      return {
        icon: "check-circle", label: `Synchron${pendingText}`, tone: "ok", spinning: false, action: "panel",
        tooltip: `Alles synchron. ${lastText}`,
      };
  }
}
