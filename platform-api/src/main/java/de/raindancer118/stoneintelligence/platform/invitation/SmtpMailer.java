package de.raindancer118.stoneintelligence.platform.invitation;

import java.nio.charset.StandardCharsets;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Versand ueber den eigenen SMTP (noreply@tstieh.de auf mx.volantic.de), Text + HTML. */
public class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final String from;

    public SmtpMailer(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(OutgoingMail mail) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(mail.to());
            helper.setSubject(mail.subject());
            helper.setText(mail.text(), mail.html());
            sender.send(message);
        } catch (MailException | jakarta.mail.MessagingException failure) {
            throw new MailDeliveryException("Mail an " + mail.to() + " nicht zustellbar", failure);
        }
    }
}
