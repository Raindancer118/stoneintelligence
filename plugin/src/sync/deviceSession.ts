import { DeviceGateError } from "./deviceGate";
import type { PluginPolicy } from "./pluginPolicy";

/** Antwort des Servers auf eine Geräte-Meldung. */
export interface DeviceReportResult {
  status: "COMPLIANT" | "NON_COMPLIANT" | "REVOKED" | "WIPE_PENDING" | "UNKNOWN";
  compliant: boolean;
  offending: string[];
  wipe: boolean;
  policy: PluginPolicy;
}

export interface DeviceSessionDeps {
  /** Meldet das Gerät (Plugins, Versionen) und liefert den Stand. */
  report(wiped: boolean): Promise<DeviceReportResult>;
  /** Merkt sich die Richtlinie für den Offline-Start. */
  rememberPolicy(policy: PluginPolicy): void;
  /** Deaktiviert nicht erlaubte Plugins; liefert deren Bezeichnungen. */
  enforce(policy: PluginPolicy): Promise<string[]>;
  /** Löscht die lokale Kopie des Vaults und meldet den Benutzer ab. */
  wipe(): Promise<void>;
}

/**
 * Vor jedem Verbindungsaufbau (ADR 0019): melden, Richtlinie durchsetzen, bei Bedarf erneut melden.
 * Wirft {@link DeviceGateError}, wenn das Gerät nicht synchronisieren darf; bei einem Löschauftrag erst
 * nach dem Löschen und der Bestätigung gegenüber dem Server.
 */
export async function prepareConnection(deps: DeviceSessionDeps): Promise<void> {
  let result = await deps.report(false);
  deps.rememberPolicy(result.policy);

  if (result.wipe) {
    await deps.wipe();
    await deps.report(true).catch(() => undefined);
    throw new DeviceGateError("DEVICE_WIPE", "Dieses Gerät wurde gesperrt, die Daten des Vaults wurden hier gelöscht.");
  }
  if (result.status === "REVOKED") {
    throw new DeviceGateError("DEVICE_REVOKED", "Dieses Gerät wurde gesperrt. Wende dich an die Verwaltung des Vaults.");
  }
  if (result.policy.mode === "ENFORCE") {
    const disabled = await deps.enforce(result.policy);
    if (disabled.length > 0 || !result.compliant) {
      result = await deps.report(false);
    }
    if (!result.compliant && result.status === "NON_COMPLIANT") {
      throw new DeviceGateError("DEVICE_NOT_COMPLIANT",
        `Nicht erlaubte Plugins sind aktiv: ${result.offending.join(", ")}. Deaktiviere sie, dann geht es weiter.`);
    }
  }
}
