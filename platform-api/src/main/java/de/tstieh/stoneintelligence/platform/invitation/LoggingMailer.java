package de.tstieh.stoneintelligence.platform.invitation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ohne SMTP-Konfiguration (lokal, Tests): nichts verschicken. Bewusst wird NUR Empfaenger und
 * Betreff geloggt - der Mailtext enthaelt den Einladungslink, und der gehoert in kein Log.
 */
public class LoggingMailer implements Mailer {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingMailer.class);

    @Override
    public void send(OutgoingMail mail) {
        LOG.info("Mailversand nicht konfiguriert - nicht gesendet an {}: {}", mail.to(), mail.subject());
    }
}
