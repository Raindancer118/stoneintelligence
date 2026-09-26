package de.tstieh.stoneintelligence.platform.invitation;

import java.time.Duration;

/** @param webappUrl Basisadresse des Dashboards - Einladungslinks zeigen auf {@code <webappUrl>/invite/<token>}. */
public record InvitationSettings(String webappUrl, Duration validity) {
}
