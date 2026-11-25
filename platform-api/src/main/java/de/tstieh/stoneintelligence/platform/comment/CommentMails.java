package de.tstieh.stoneintelligence.platform.comment;

import de.tstieh.stoneintelligence.platform.invitation.OutgoingMail;
import org.springframework.web.util.HtmlUtils;

/** Die Mail zu einer Erwähnung, deutsch, Text + schlichtes HTML wie die Einladungsmails. */
final class CommentMails {

    private CommentMails() {
    }

    static OutgoingMail mention(String to, String actorName, String noteTitle, String excerpt, String webappUrl) {
        var subject = oneLine(actorName + " hat dich in „" + noteTitle + "“ erwähnt");
        var text = """
            Hallo,

            %s hat dich in einem Kommentar zur Notiz „%s“ erwähnt:

            %s

            Öffne StoneIntelligence im Browser oder in Obsidian (Ansicht „Kommentare“), um zu antworten:
            %s
            """.formatted(actorName, noteTitle, excerpt, webappUrl);
        var html = """
            <!doctype html><html lang="de"><body style="margin:0;background:#eceff4;font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;color:#1f2937">
            <div style="max-width:560px;margin:32px auto;background:#fff;border-radius:10px;padding:32px">
            <h1 style="font-family:Georgia,serif;font-weight:normal;font-size:22px;margin:0 0 16px">%s</h1>
            <blockquote style="margin:16px 0;padding:8px 16px;border-left:3px solid #c5c5c5;color:#374151">%s</blockquote>
            <p style="margin:24px 0"><a href="%s" style="background:#181b1f;color:#fff;padding:12px 20px;border-radius:6px;text-decoration:none;display:inline-block">Kommentar öffnen</a></p>
            </div></body></html>
            """.formatted(esc(subject), esc(excerpt), esc(webappUrl));
        return new OutgoingMail(to, subject, text, html);
    }

    private static String oneLine(String value) {
        return value.replaceAll("[\\r\\n]+", " ");
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
