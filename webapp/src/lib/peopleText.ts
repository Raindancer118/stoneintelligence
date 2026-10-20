const UUID_LIKE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Name einer Person; das Subject nur dazu, wenn es mehr sagt als eine ID (ADR 0013). */
export function personLabel(subject: string, displayName: string | null | undefined): string {
  if (!displayName || displayName === subject) {
    return subject;
  }
  return UUID_LIKE.test(subject) ? displayName : `${displayName} (${subject})`;
}

export function idpLinkText(group: { idpGroup: string | null; idpMemberCount: number | null }): string | null {
  if (!group.idpGroup) {
    return null;
  }
  const count = group.idpMemberCount ?? 0;
  const people = count === 0 ? "noch niemand angemeldet" : count === 1 ? "1 Person" : `${count.toLocaleString("de-DE")} Personen`;
  return `Verknüpft mit „${group.idpGroup}“ beim Identitätsanbieter – ${people}`;
}
