package de.raindancer118.stoneintelligence.platform.invitation;

import java.time.Clock;
import java.time.Duration;
import java.util.Properties;
import de.raindancer118.stoneintelligence.platform.identity.AuthorizationRepository;
import de.raindancer118.stoneintelligence.platform.vault.VaultAccessGuard;
import de.raindancer118.stoneintelligence.platform.vault.VaultRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Alles ueber Umgebungsvariablen (auf dorn in der Stack-.env): ohne Authentik-Token gibt es keine
 * Personensuche, ohne SMTP-Host werden Mails nur protokolliert - die Anwendung startet in beiden
 * Faellen normal.
 */
@Configuration
@EnableScheduling
public class InvitationConfig {

    @Bean
    public UserDirectory userDirectory(
        @Value("${STONEINTELLIGENCE_AUTHENTIK_URL:}") String url,
        @Value("${STONEINTELLIGENCE_AUTHENTIK_TOKEN:}") String token,
        @Value("${STONEINTELLIGENCE_AUTHENTIK_INVITATION_FLOW:stoneintelligence-invitation}") String flow,
        @Value("${STONEINTELLIGENCE_AUTHENTIK_DIRECTORY_GROUP:User}") String group
    ) {
        return url.isBlank() || token.isBlank() ? new DisabledUserDirectory() : new AuthentikUserDirectory(url, token, flow, group);
    }

    @Bean
    public Mailer mailer(
        @Value("${STONEINTELLIGENCE_MAIL_HOST:}") String host,
        @Value("${STONEINTELLIGENCE_MAIL_PORT:587}") int port,
        @Value("${STONEINTELLIGENCE_MAIL_USERNAME:}") String username,
        @Value("${STONEINTELLIGENCE_MAIL_PASSWORD:}") String password,
        @Value("${STONEINTELLIGENCE_MAIL_FROM:}") String from
    ) {
        if (host.isBlank()) {
            return new LoggingMailer();
        }
        var sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        var properties = new Properties();
        properties.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
        // 465 = TLS von Anfang an, sonst STARTTLS erzwingen - nie unverschluesselt.
        if (port == 465) {
            properties.put("mail.smtp.ssl.enable", "true");
        } else {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
        }
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "15000");
        properties.put("mail.smtp.writetimeout", "15000");
        sender.setJavaMailProperties(properties);
        return new SmtpMailer(sender, from.isBlank() ? username : from);
    }

    @Bean
    public InvitationService invitationService(
        InvitationRepository invitations, UserDirectory directory, Mailer mailer, AuthorizationRepository authorization,
        VaultAccessGuard access, VaultRepository vaults,
        @Value("${STONEINTELLIGENCE_WEBAPP_URL:https://kb.tstieh.de}") String webappUrl
    ) {
        return new InvitationService(invitations, directory, mailer, authorization, access, vaults, Clock.systemUTC(),
            new InvitationSettings(webappUrl, Duration.ofDays(14)));
    }

    /** Taeglich: abgeschlossene Einladungen (inkl. E-Mail-Adresse) nach 30 Tagen entfernen. */
    @Bean
    public InvitationHousekeeping invitationHousekeeping(InvitationService invitations) {
        return new InvitationHousekeeping(invitations);
    }

    public static class InvitationHousekeeping {
        private final InvitationService invitations;

        InvitationHousekeeping(InvitationService invitations) {
            this.invitations = invitations;
        }

        @Scheduled(initialDelay = 60_000, fixedDelay = 24 * 60 * 60 * 1000)
        public void purge() {
            invitations.purgeClosed();
        }
    }
}
