package de.tstieh.stoneintelligence.platform.invitation;

public interface Mailer {

    void send(OutgoingMail mail);

    /** {@code false}, wenn nur protokolliert wird (kein SMTP-Host) - Mails, die niemand braucht, entfallen dann ganz. */
    default boolean isConfigured() {
        return true;
    }
}
