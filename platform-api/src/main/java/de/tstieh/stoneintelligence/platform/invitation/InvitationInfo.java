package de.tstieh.stoneintelligence.platform.invitation;

import java.time.Instant;

/** Oeffentlich (ohne Login) abrufbar - enthaelt deshalb nur, was die eingeladene Person ohnehin per Mail hat. */
public record InvitationInfo(InvitationState state, String vaultName, String invitedBy, String maskedEmail,
                             InviteAccess access, Instant expiresAt, String enrollmentUrl) {
}
