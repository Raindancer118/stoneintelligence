package de.tstieh.stoneintelligence.platform.invitation;

import java.util.ArrayList;
import java.util.List;

public final class RecordingMailer implements Mailer {

    public final List<OutgoingMail> sent = new ArrayList<>();
    boolean failing;

    @Override
    public void send(OutgoingMail mail) {
        if (failing) {
            throw new MailDeliveryException("SMTP nicht erreichbar", new RuntimeException());
        }
        sent.add(mail);
    }
}
