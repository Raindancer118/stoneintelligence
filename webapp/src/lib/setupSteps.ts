export interface SetupStep { id: "note" | "invite" | "obsidian"; label: string; hint: string; done: boolean; }

const key = (kind: string, vaultId: string) => `stone.setup.${kind}.${vaultId}`;

function read(name: string): boolean {
  try { return localStorage.getItem(name) === "1"; } catch { return false; }
}
function write(name: string) {
  try { localStorage.setItem(name, "1"); } catch { /* privater Modus: dann eben jedes Mal */ }
}

/** Ob jemand Obsidian verbunden hat, sieht der Server nicht - gezählt wird der geöffnete Verbinden-Link. */
export function markConnectOpened(vaultId: string) { write(key("obsidian", vaultId)); }
export function dismissSetup(vaultId: string) { write(key("dismissed", vaultId)); }
export function setupDismissed(vaultId: string) { return read(key("dismissed", vaultId)); }

/** `members === null`: unbekannt (keine Verwaltungsrechte). */
export function setupSteps(state: { vaultId: string; hasNotes: boolean; members: number | null; canManage: boolean }): SetupStep[] {
  const steps: SetupStep[] = [
    { id: "note", label: "Erste Notiz anlegen", hint: "Oben links über „Neue Notiz“.", done: state.hasNotes },
  ];
  if (state.canManage) {
    steps.push({ id: "invite", label: "Leute einladen", hint: "Unter „Mitglieder & Rechte“ per Name oder E-Mail.", done: (state.members ?? 0) > 1 });
  }
  steps.push({ id: "obsidian", label: "Obsidian verbinden", hint: "Ein Klick verbindet das Plugin mit diesem Vault.", done: read(key("obsidian", state.vaultId)) });
  return steps;
}
