/** Der Server verweigert dem Gerät die Synchronisation (ADR 0019); `code` ist der Maschinencode, `message` die deutsche Begründung. */
export type DeviceGateCode = "DEVICE_UNKNOWN" | "DEVICE_REVOKED" | "DEVICE_WIPE" | "DEVICE_NOT_COMPLIANT";

export class DeviceGateError extends Error {
  constructor(readonly code: DeviceGateCode, message: string) {
    super(message);
    this.name = "DeviceGateError";
  }
}

const CODES: DeviceGateCode[] = ["DEVICE_UNKNOWN", "DEVICE_REVOKED", "DEVICE_WIPE", "DEVICE_NOT_COMPLIANT"];

/** Erkennt die 403-Antwort des Servers mit {@code code}; alles andere bleibt ein gewöhnlicher Fehler. */
export async function gateErrorFrom(response: Pick<Response, "status" | "json">): Promise<DeviceGateError | null> {
  if (response.status !== 403) {
    return null;
  }
  try {
    const body = (await response.json()) as { code?: unknown; detail?: unknown };
    const code = CODES.find((candidate) => candidate === body.code);
    return code ? new DeviceGateError(code, typeof body.detail === "string" ? body.detail : code) : null;
  } catch {
    return null;
  }
}
