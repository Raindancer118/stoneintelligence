/**
 * Stabile Cursor-Farbe je Anzeigename - dieselbe Person bekommt auf jedem Geraet/in jeder
 * Session dieselbe Farbe, ohne Server-Zuweisung oder gespeicherten Zustand noetig. Uebernommen
 * aus dem Vorgaenger-Projekt `stonesync` (`plugin/src/settings/userColor.ts`), das dieses Muster
 * bereits produktiv einsetzte.
 */
const CURSOR_COLORS = [
  "#e57373",
  "#64b5f6",
  "#81c784",
  "#ffb74d",
  "#ba68c8",
  "#4db6ac",
  "#f06292",
  "#a1887f",
];

export function pickUserColor(seed: string): string {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  }
  return CURSOR_COLORS[hash % CURSOR_COLORS.length];
}

/**
 * Dekodiert die Claims eines JWT OHNE Signaturpruefung - reicht hier aus, weil das Ergebnis nur
 * zur Anzeige dient (Cursor-Label), nie fuer eine Autorisierungsentscheidung (die trifft
 * ausschliesslich der Server anhand des tatsaechlichen, validierten Tokens). Gibt `null` zurueck
 * bei jedem Parsing-Fehler (kein JWT, fehlendes Payload-Segment, ungueltiges Base64/JSON) - nie
 * werfend, ein defektes/abgelaufenes Token darf die Cursor-Anzeige nicht zum Absturz bringen.
 */
export function decodeJwtClaims(token: string): Record<string, unknown> | null {
  const segments = token.split(".");
  if (segments.length < 2) {
    return null;
  }
  try {
    const base64 = segments[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const json = atob(padded);
    const parsed = JSON.parse(json) as unknown;
    return typeof parsed === "object" && parsed !== null ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Fuer Anzeigezwecke brauchbare Claims, in absteigender Praeferenz. */
const DISPLAY_NAME_CLAIMS = ["name", "preferred_username", "email", "sub"] as const;

/**
 * Waehlt den anzuzeigenden Namen aus OIDC-Claims. `name` zuerst, weil Authentik dort den vollen
 * Anzeigenamen fuehrt ("Tom Stieh") - als Cursor-Label lesbarer als ein Login-Kuerzel. Danach
 * `preferred_username`, derselbe Claim, aus dem der Server die Actor-Identitaet ableitet
 * (SecurityConfig: `setPrincipalClaimName("preferred_username")`), dann E-Mail, dann `sub`.
 *
 * <p>Nicht-String-Claims werden bewusst ignoriert statt stringifiziert - ein Cursor-Label
 * "[object Object]" waere schlechter als das ehrliche "Unbekannt".
 */
export function displayNameFromClaims(claims: Record<string, unknown> | null | undefined): string {
  for (const claim of DISPLAY_NAME_CLAIMS) {
    const value = claims?.[claim];
    if (typeof value === "string" && value.length > 0) {
      return value;
    }
  }
  return "Unbekannt";
}

/**
 * Leitet den fuer Awareness/Cursor anzuzeigenden Namen aus dem `preferred_username`-Claim des
 * Access-Tokens ab - demselben Claim, aus dem der Server serverseitig die Actor-Identitaet
 * ableitet (s. NoteController-Doc: "Actor-Identitaet ... aus dem preferred_username-Claim"),
 * damit der angezeigte Name mit dem tatsaechlichen Actor uebereinstimmt.
 */
export function actorDisplayNameFromAccessToken(accessToken: string | null | undefined): string {
  if (!accessToken) {
    return "Unbekannt";
  }
  return displayNameFromClaims(decodeJwtClaims(accessToken));
}
