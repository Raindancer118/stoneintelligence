package de.tstieh.stoneintelligence.platform.invitation;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.web.util.HtmlUtils;

/** Die zwei Mails des Einladens, deutsch, Text + schlichtes HTML (gleiches Layout wie die Authentik-Mails). */
final class InvitationMails {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN)
        .withZone(ZoneId.of("Europe/Berlin"));

    private InvitationMails() {
    }

    static OutgoingMail invitation(String to, String vaultName, String invitedBy, InviteAccess access, String link, Instant expiresAt) {
        var subject = "Einladung zu „" + vaultName + "“";
        var text = """
            Hallo,

            %s lädt dich ein, im Vault „%s“ bei StoneIntelligence mitzuarbeiten (Notizen %s).

            Einladung annehmen:
            %s

            Hast du noch kein Konto, legst du es dort in einer Minute an und nimmst die Einladung danach an.
            Der Link gilt bis zum %s.

            Wenn du mit dieser Einladung nichts anfangen kannst, ignoriere diese Mail einfach.
            """.formatted(invitedBy, vaultName, access.label(), link, DATE.format(expiresAt));
        var html = layout(subject,
            "<p><strong>%s</strong> lädt dich ein, im Vault <strong>„%s“</strong> bei StoneIntelligence mitzuarbeiten (Notizen %s).</p>"
                .formatted(esc(invitedBy), esc(vaultName), access.label())
                + button(link, "Einladung annehmen")
                + "<p>Hast du noch kein Konto, legst du es dort in einer Minute an und nimmst die Einladung danach an. "
                + "Der Link gilt bis zum %s.</p>".formatted(DATE.format(expiresAt))
                + "<p style=\"color:#6b7280;font-size:13px\">Wenn du mit dieser Einladung nichts anfangen kannst, ignoriere diese Mail einfach.</p>");
        return new OutgoingMail(to, subject, text, html);
    }

    static OutgoingMail added(String to, String name, String vaultName, String addedBy, InviteAccess access, String webappUrl) {
        var subject = "Du bist jetzt im Vault „" + vaultName + "“";
        var text = """
            Hallo %s,

            %s hat dich zum Vault „%s“ bei StoneIntelligence hinzugefügt (Notizen %s).

            Im Obsidian-Plugin wählst du ihn in den Einstellungen unter „Vault“, im Browser findest du ihn hier:
            %s
            """.formatted(name, addedBy, vaultName, access.label(), webappUrl);
        var html = layout(subject,
            "<p>Hallo %s,</p><p><strong>%s</strong> hat dich zum Vault <strong>„%s“</strong> bei StoneIntelligence hinzugefügt (Notizen %s).</p>"
                .formatted(esc(name), esc(addedBy), esc(vaultName), access.label())
                + "<p>Im Obsidian-Plugin wählst du ihn in den Einstellungen unter „Vault“.</p>"
                + button(webappUrl, "Im Browser öffnen"));
        return new OutgoingMail(to, subject, text, html);
    }

    private static String button(String href, String label) {
        return "<p style=\"margin:24px 0\"><a href=\"%s\" style=\"background:#0d9488;color:#fff;padding:12px 20px;border-radius:6px;text-decoration:none;display:inline-block\">%s</a></p>"
            .formatted(esc(href), esc(label));
    }

    private static String layout(String title, String body) {
        return """
            <!doctype html><html lang="de"><body style="margin:0;background:#eceff4;font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;color:#1f2937">
            <div style="max-width:560px;margin:32px auto;background:#fff;border-radius:10px;padding:32px">
            <h1 style="font-family:Georgia,serif;font-weight:normal;font-size:22px;margin:0 0 16px">%s</h1>%s
            </div></body></html>
            """.formatted(esc(title), body);
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
