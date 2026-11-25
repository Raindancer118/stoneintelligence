package de.tstieh.stoneintelligence.platform.comment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.audit.AuditRecorder;
import de.tstieh.stoneintelligence.platform.identity.PeopleDirectory;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.invitation.Mailer;
import de.tstieh.stoneintelligence.platform.invitation.UserDirectory;
import de.tstieh.stoneintelligence.platform.vault.ForbiddenException;
import de.tstieh.stoneintelligence.platform.vault.Note;
import de.tstieh.stoneintelligence.platform.vault.NoteNotFoundException;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;
import de.tstieh.stoneintelligence.platform.vault.VaultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Kommentare zu Notizen (ADR 0016). Jede Operation prüft zuerst das Leserecht auf die Notiz - wer sie
 * lesen darf, darf kommentieren; wer sie nicht lesen darf, erfährt weder Text noch Dasein der Kommentare.
 */
@Service
public class CommentService {

    static final int MAX_BODY = 4000;
    static final int NOTIFICATION_LIMIT = 100;
    private static final int EXCERPT = 160;
    private static final Logger LOG = LoggerFactory.getLogger(CommentService.class);

    public record CommentView(String id, String author, String authorName, boolean mine, String body, Instant createdAt, Instant editedAt) {
    }

    public record ThreadView(String id, String noteId, Anchor anchor, boolean resolved, String resolvedByName, Instant resolvedAt,
                             Instant createdAt, List<CommentView> comments) {
    }

    public record NotificationView(String id, String vaultId, String vaultName, String noteId, String notePath, String threadId,
                                   String commentId, String actorName, String excerpt, Instant createdAt, boolean read) {
    }

    public record NotificationList(List<NotificationView> items, int unread) {
    }

    private final CommentRepository comments;
    private final NoteRepository notes;
    private final VaultAccessGuard access;
    private final PeopleDirectory people;
    private final VaultRepository vaults;
    private final AuditRecorder audit;
    private final UserDirectory directory;
    private final Mailer mailer;
    private final String webappUrl;

    public CommentService(CommentRepository comments, NoteRepository notes, VaultAccessGuard access, PeopleDirectory people,
                          VaultRepository vaults, AuditRecorder audit, UserDirectory directory, Mailer mailer,
                          @Value("${STONEINTELLIGENCE_WEBAPP_URL:https://kb.tstieh.de}") String webappUrl) {
        this.comments = comments;
        this.notes = notes;
        this.access = access;
        this.people = people;
        this.vaults = vaults;
        this.audit = audit;
        this.directory = directory;
        this.mailer = mailer;
        this.webappUrl = webappUrl;
    }

    public List<ThreadView> list(VaultId vaultId, NoteId noteId, String actor) {
        readable(vaultId, noteId, actor);
        return views(comments.threadsOf(vaultId, noteId), actor);
    }

    @Transactional
    public ThreadView startThread(VaultId vaultId, NoteId noteId, String actor, String body, Anchor anchor) {
        var note = readable(vaultId, noteId, actor);
        if (note.isFile()) {
            throw new CommentRejectedException("Dateien lassen sich nicht kommentieren.");
        }
        if (note.level().isE2ee()) {
            throw new CommentRejectedException("Verschlüsselte Notizen lassen sich nicht kommentieren.");
        }
        var text = validBody(body);
        if (anchor != null && !anchor.valid()) {
            throw new CommentRejectedException("Die markierte Textstelle ist leer oder zu lang.");
        }
        var mentions = checkedMentions(vaultId, note, text, Set.of());
        var thread = comments.createThread(vaultId, noteId, anchor, actor, text);
        var comment = thread.comments().getFirst();
        audit.record(vaultId, noteId, actor, "comment.created", Map.of("threadId", thread.id().toString(), "commentId", comment.id().toString()));
        notifyMentioned(vaultId, note, thread.id(), comment, actor, mentions);
        return views(List.of(thread), actor).getFirst();
    }

    @Transactional
    public ThreadView reply(VaultId vaultId, UUID threadId, String actor, String body) {
        var thread = readableThread(vaultId, threadId, actor);
        var note = readable(vaultId, thread.noteId(), actor);
        var text = validBody(body);
        var mentions = checkedMentions(vaultId, note, text, Set.of());
        var reply = comments.addReply(vaultId, threadId, actor, text).orElseThrow(() -> notFound());
        audit.record(vaultId, note.id(), actor, "comment.created", Map.of("threadId", threadId.toString(), "commentId", reply.id().toString()));
        notifyMentioned(vaultId, note, threadId, reply, actor, mentions);
        return views(List.of(comments.findThread(vaultId, threadId).orElseThrow(() -> notFound())), actor).getFirst();
    }

    @Transactional
    public ThreadView edit(VaultId vaultId, UUID commentId, String actor, String body) {
        var thread = threadOfComment(vaultId, commentId, actor);
        var note = readable(vaultId, thread.noteId(), actor);
        var comment = thread.comments().stream().filter(c -> c.id().equals(commentId)).findFirst().orElseThrow(() -> notFound());
        if (!comment.author().equals(actor)) {
            throw new ForbiddenException(actor + " may only edit their own comments");
        }
        var text = validBody(body);
        var mentions = checkedMentions(vaultId, note, text, Mentions.of(comment.body()).keySet());
        var edited = comments.editComment(vaultId, commentId, text).orElseThrow(() -> notFound());
        audit.record(vaultId, note.id(), actor, "comment.edited", Map.of("threadId", thread.id().toString(), "commentId", commentId.toString()));
        notifyMentioned(vaultId, note, thread.id(), edited, actor, mentions);
        return views(List.of(comments.findThread(vaultId, thread.id()).orElseThrow(() -> notFound())), actor).getFirst();
    }

    @Transactional
    public void delete(VaultId vaultId, UUID commentId, String actor) {
        var thread = threadOfComment(vaultId, commentId, actor);
        var note = readable(vaultId, thread.noteId(), actor);
        var comment = thread.comments().stream().filter(c -> c.id().equals(commentId)).findFirst().orElseThrow(() -> notFound());
        if (!comment.author().equals(actor) && !access.accessAt(vaultId, actor, note.path()).allows(Permission.MANAGE)) {
            throw new ForbiddenException(actor + " may only delete their own comments");
        }
        comments.deleteComment(vaultId, commentId);
        audit.record(vaultId, note.id(), actor, "comment.deleted", Map.of("threadId", thread.id().toString(), "commentId", commentId.toString()));
    }

    @Transactional
    public ThreadView resolve(VaultId vaultId, UUID threadId, String actor) {
        return setResolved(vaultId, threadId, actor, true);
    }

    @Transactional
    public ThreadView reopen(VaultId vaultId, UUID threadId, String actor) {
        return setResolved(vaultId, threadId, actor, false);
    }

    private ThreadView setResolved(VaultId vaultId, UUID threadId, String actor, boolean resolved) {
        var thread = readableThread(vaultId, threadId, actor);
        readable(vaultId, thread.noteId(), actor);
        var updated = comments.setResolved(vaultId, threadId, resolved ? actor : null).orElseThrow(() -> notFound());
        audit.record(vaultId, thread.noteId(), actor, resolved ? "comment.resolved" : "comment.reopened", Map.of("threadId", threadId.toString()));
        return views(List.of(updated), actor).getFirst();
    }

    /** Nur Hinweise auf Notizen, die {@code actor} (noch) lesen darf; {@code unread} zählt nur diese. */
    public NotificationList notifications(String actor) {
        var visible = new ArrayList<CommentNotification>();
        var checkers = new HashMap<VaultId, java.util.function.Function<String, de.tstieh.stoneintelligence.platform.identity.EffectiveAccess>>();
        var noteCache = new HashMap<NoteId, Note>();
        for (var item : comments.notificationsFor(actor, NOTIFICATION_LIMIT)) {
            if (!access.membership(item.vaultId(), actor).isMember()) {
                continue;
            }
            var note = noteCache.computeIfAbsent(item.noteId(), id -> notes.findById(item.vaultId(), id).orElse(null));
            if (note == null) {
                continue;
            }
            var check = checkers.computeIfAbsent(item.vaultId(), vault -> access.accessChecker(vault, actor));
            if (check.apply(note.path()).allows(Permission.READ)) {
                visible.add(item);
            }
        }
        var names = names(visible.stream().map(CommentNotification::actor).toList());
        var views = new ArrayList<NotificationView>();
        var vaultNames = new HashMap<VaultId, String>();
        for (var item : visible) {
            var thread = comments.findThread(item.vaultId(), item.threadId());
            var comment = thread.flatMap(t -> t.comments().stream().filter(c -> c.id().equals(item.commentId())).findFirst());
            if (comment.isEmpty()) {
                continue;
            }
            views.add(new NotificationView(item.id().toString(), item.vaultId().value().toString(),
                vaultNames.computeIfAbsent(item.vaultId(), id -> vaults.findById(id).map(v -> v.name()).orElse("")),
                item.noteId().value().toString(), noteCache.get(item.noteId()).path(), item.threadId().toString(),
                item.commentId().toString(), names.getOrDefault(item.actor(), "Jemand"), excerpt(comment.get().body()),
                item.createdAt(), item.readAt() != null));
        }
        return new NotificationList(views, (int) views.stream().filter(v -> !v.read()).count());
    }

    public void markRead(String actor, Collection<UUID> ids) {
        comments.markRead(actor, ids);
    }

    private Note readable(VaultId vaultId, NoteId noteId, String actor) {
        access.requireMember(vaultId, actor);
        var note = notes.findById(vaultId, noteId).orElseThrow(() -> new NoteNotFoundException(vaultId, noteId));
        access.require(vaultId, actor, Permission.READ, note.path());
        return note;
    }

    private CommentThread readableThread(VaultId vaultId, UUID threadId, String actor) {
        access.requireMember(vaultId, actor);
        return comments.findThread(vaultId, threadId).orElseThrow(() -> notFound());
    }

    private CommentThread threadOfComment(VaultId vaultId, UUID commentId, String actor) {
        access.requireMember(vaultId, actor);
        return comments.findThreadOfComment(vaultId, commentId).orElseThrow(() -> notFound());
    }

    private static CommentNotFoundException notFound() {
        return new CommentNotFoundException("no such comment");
    }

    private static String validBody(String body) {
        var text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            throw new CommentRejectedException("Ein Kommentar darf nicht leer sein.");
        }
        if (text.length() > MAX_BODY) {
            throw new CommentRejectedException("Ein Kommentar darf höchstens " + MAX_BODY + " Zeichen lang sein.");
        }
        return text;
    }

    /** Erwähnte Personen, die neu sind und benachrichtigt werden; wirft, wenn eine die Notiz nicht lesen darf. */
    private Set<String> checkedMentions(VaultId vaultId, Note note, String body, Set<String> alreadyMentioned) {
        var fresh = new LinkedHashSet<String>();
        for (var mention : Mentions.of(body).entrySet()) {
            if (alreadyMentioned.contains(mention.getKey())) {
                continue;
            }
            var subject = mention.getKey();
            if (!access.membership(vaultId, subject).isMember()) {
                throw new CommentRejectedException(mention.getValue() + " ist kein Mitglied dieses Vaults und lässt sich nicht erwähnen.");
            }
            if (!access.accessAt(vaultId, subject, note.path()).allows(Permission.READ)) {
                throw new CommentRejectedException(mention.getValue() + " darf diese Notiz nicht lesen und lässt sich deshalb nicht erwähnen.");
            }
            fresh.add(subject);
        }
        return fresh;
    }

    private void notifyMentioned(VaultId vaultId, Note note, UUID threadId, Comment comment, String actor, Set<String> mentioned) {
        var recipients = new HashSet<>(mentioned);
        recipients.remove(actor);
        if (recipients.isEmpty()) {
            return;
        }
        for (var recipient : recipients) {
            comments.addNotification(vaultId, note.id(), threadId, comment.id(), recipient, actor);
        }
        if (!mailer.isConfigured()) {
            return;
        }
        var actorName = names(List.of(actor)).getOrDefault(actor, "Jemand");
        var title = title(note.path());
        var preview = excerpt(comment.body());
        afterCommit(() -> {
            for (var recipient : recipients) {
                mail(recipient, actorName, title, preview);
            }
        });
    }

    private void mail(String recipient, String actorName, String title, String preview) {
        try {
            if (!directory.isAvailable()) {
                return;
            }
            var email = directory.findByUsername(recipient).map(user -> user.email()).filter(address -> address != null && !address.isBlank());
            if (email.isPresent()) {
                mailer.send(CommentMails.mention(email.get(), actorName, title, preview, webappUrl));
            }
        } catch (RuntimeException failure) {
            // Der Kommentar und der Hinweis in der App gelten trotzdem.
            LOG.warn("Erwähnungs-Mail an {} nicht zustellbar", recipient, failure);
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private List<ThreadView> views(List<CommentThread> threads, String actor) {
        var subjects = new HashSet<String>();
        threads.forEach(thread -> {
            thread.comments().forEach(comment -> subjects.add(comment.author()));
            if (thread.resolvedBy() != null) {
                subjects.add(thread.resolvedBy());
            }
        });
        var names = names(subjects);
        return threads.stream().map(thread -> new ThreadView(thread.id().toString(), thread.noteId().value().toString(), thread.anchor(),
            thread.resolved(), thread.resolvedBy() == null ? null : names.getOrDefault(thread.resolvedBy(), "Unbekannt"),
            thread.resolvedAt(), thread.createdAt(),
            thread.comments().stream().map(comment -> new CommentView(comment.id().toString(), comment.author(),
                names.getOrDefault(comment.author(), "Unbekannt"), comment.author().equals(actor), comment.body(),
                comment.createdAt(), comment.editedAt())).toList())).toList();
    }

    private Map<String, String> names(Collection<String> subjects) {
        return subjects.isEmpty() ? Map.of() : people.displayNames(subjects);
    }

    private static String excerpt(String body) {
        var plain = Mentions.plain(body).replaceAll("\\s+", " ").strip();
        return plain.length() <= EXCERPT ? plain : plain.substring(0, EXCERPT - 1).stripTrailing() + "…";
    }

    private static String title(String path) {
        var name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }
}
