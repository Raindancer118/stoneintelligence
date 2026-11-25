package de.tstieh.stoneintelligence.platform.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;
import de.tstieh.stoneintelligence.platform.ai.AiAuditRecorder;
import de.tstieh.stoneintelligence.platform.ai.AiJob;
import de.tstieh.stoneintelligence.platform.ai.AiJobGoneException;
import de.tstieh.stoneintelligence.platform.ai.AiJobRepository;
import de.tstieh.stoneintelligence.platform.ai.AiJobService;
import de.tstieh.stoneintelligence.platform.ai.AiService;
import de.tstieh.stoneintelligence.platform.ai.AiServiceDirectory;
import de.tstieh.stoneintelligence.platform.ai.AiWriteRefusedException;
import de.tstieh.stoneintelligence.platform.identity.Permission;
import de.tstieh.stoneintelligence.platform.vault.NoteRepository;
import de.tstieh.stoneintelligence.platform.vault.VaultAccessGuard;

/**
 * "Frag den Vault" (ADR 0017): nimmt Fragen an, gibt dem Worker die Textstellen und speichert seine Antwort mit den
 * Zitaten. Fragen und Antworten sehen nur ihre Fragenden; beim Lesen gilt immer der aktuelle Zugriff.
 */
public class AskService {

    public static final int MAX_QUESTION_LENGTH = 1_000;
    static final int MIN_QUESTION_LENGTH = 5;
    static final int MAX_OPEN_PER_PERSON = 3;
    /** multilingual-e5-small, wie bei den gespeicherten Abschnitten (ADR 0012). */
    private static final int EMBEDDING_DIMENSIONS = 384;
    private static final int HISTORY_LIMIT = 50;
    private static final int EXCERPT_LENGTH = 240;
    private static final int MAX_ANSWER_LENGTH = 20_000;
    /** {@code [1]} oder {@code [1, 2]}. */
    private static final Pattern CITATION = Pattern.compile("\\[(\\d{1,2}(?:\\s*,\\s*\\d{1,2})*)]");

    /** @param excerpt kurzer Auszug der zitierten Stelle, damit die Oberflaeche sie zeigen kann, ohne die Notiz zu laden */
    public record Citation(int n, NoteId noteId, String path, String heading, String excerpt) { }

    /**
     * Eine Frage, wie die fragende Person sie sieht. {@code restricted}: eine zitierte Notiz ist nicht mehr lesbar
     * oder gelöscht - dann ist die Antwort verborgen ({@code question.answer()} bleibt in der Ansicht leer).
     */
    public record View(Question question, AiJob job, List<Citation> citations, boolean restricted) {

        public String status() {
            if (question.answered() != null) {
                return question.answered() ? "ANSWERED" : "NOT_ANSWERABLE";
            }
            if (job == null) {
                return "FAILED";
            }
            return switch (job.status()) {
                case PENDING -> "PENDING";
                case RUNNING -> "RUNNING";
                case CANCELLED -> "CANCELLED";
                case SUCCEEDED, FAILED -> "FAILED";
            };
        }
    }

    /** Was der Worker bekommt: die nummerierten Stellen und ob der Dienst lokal ist. */
    public record Passages(List<AskRetrieval.Passage> passages, boolean local, int omittedForConsent) { }

    private final QuestionRepository questions;
    private final AiJobService jobs;
    private final AiJobRepository jobRepository;
    private final AiServiceDirectory services;
    private final AskRetrieval retrieval;
    private final VaultAccessGuard access;
    private final NoteRepository notes;
    private final Function<VaultId, Set<String>> consents;
    private final AiAuditRecorder audit;
    private final Supplier<Instant> clock;

    public AskService(QuestionRepository questions, AiJobService jobs, AiJobRepository jobRepository, AiServiceDirectory services,
                      AskRetrieval retrieval, VaultAccessGuard access, NoteRepository notes, Function<VaultId, Set<String>> consents,
                      AiAuditRecorder audit, Supplier<Instant> clock) {
        this.questions = questions;
        this.jobs = jobs;
        this.jobRepository = jobRepository;
        this.services = services;
        this.retrieval = retrieval;
        this.access = access;
        this.notes = notes;
        this.consents = consents;
        this.audit = audit;
        this.clock = clock;
    }

    /** @param serviceId {@code null}: ein lokaler Dienst, sonst der erste eingerichtete */
    public View ask(VaultId vaultId, String actor, String text, String serviceId) {
        access.requireMember(vaultId, actor);
        var question = text == null ? "" : text.strip();
        if (question.length() < MIN_QUESTION_LENGTH || question.length() > MAX_QUESTION_LENGTH) {
            throw new AiWriteRefusedException("Die Frage braucht " + MIN_QUESTION_LENGTH + " bis " + MAX_QUESTION_LENGTH + " Zeichen");
        }
        var service = serviceId == null ? defaultService() : services.find(serviceId)
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + serviceId + " ist nicht eingerichtet"));
        var open = questions.listBy(vaultId, actor, HISTORY_LIMIT).stream()
            .filter(own -> jobRepository.findById(own.jobId()).map(AiJob::open).orElse(false)).count();
        if (open >= MAX_OPEN_PER_PERSON) {
            throw new AiWriteRefusedException("Du hast schon " + MAX_OPEN_PER_PERSON + " offene Fragen - warte, bis eine beantwortet ist");
        }
        var job = jobs.startAsk(vaultId, actor, service.id());
        return view(questions.create(Question.asked(vaultId, actor, service.id(), question, job.id(), clock.get())), actor);
    }

    public List<View> list(VaultId vaultId, String actor) {
        access.requireMember(vaultId, actor);
        return questions.listBy(vaultId, actor, HISTORY_LIMIT).stream().map(question -> view(question, actor)).toList();
    }

    public View get(VaultId vaultId, String actor, UUID id) {
        access.requireMember(vaultId, actor);
        return view(own(vaultId, actor, id), actor);
    }

    public View cancel(VaultId vaultId, String actor, UUID id) {
        access.requireMember(vaultId, actor);
        var question = own(vaultId, actor, id);
        if (!jobs.cancel(vaultId, question.jobId(), actor)) {
            throw new AiWriteRefusedException("Nur wartende oder laufende Fragen lassen sich abbrechen");
        }
        return view(question, actor);
    }

    public void delete(VaultId vaultId, String actor, UUID id) {
        access.requireMember(vaultId, actor);
        var question = own(vaultId, actor, id);
        if (jobRepository.findById(question.jobId()).map(AiJob::open).orElse(false)) {
            throw new AiWriteRefusedException("Die Frage läuft noch - brich sie erst ab");
        }
        questions.delete(vaultId, actor, id);
    }

    /** Fuer den Worker: die Frage eines laufenden ASK-Jobs. */
    public String questionText(UUID jobId) {
        return forJob(jobId).question();
    }

    /**
     * Fuer den Worker: die Stellen zur Frage, mit den Rechten der fragenden Person und der Einwilligungsregel des Dienstes.
     * Merkt sich die Stellen als Quellen und schreibt das Audit-Ereignis.
     */
    public Passages passages(UUID jobId, float[] questionVector) {
        var question = forJob(jobId);
        var vaultId = question.vaultId();
        if (questionVector != null) {
            if (questionVector.length != EMBEDDING_DIMENSIONS) {
                throw new AiWriteRefusedException("Der Vektor der Frage braucht " + EMBEDDING_DIMENSIONS + " Werte");
            }
            for (var value : questionVector) {
                if (!Float.isFinite(value)) {
                    throw new AiWriteRefusedException("Vektoren dürfen nur endliche Zahlen enthalten");
                }
            }
        }
        if (!access.membership(vaultId, question.askedBy()).isMember()) {
            throw new AiWriteRefusedException("Die fragende Person ist nicht mehr Mitglied dieses Vaults");
        }
        var service = services.find(question.service())
            .orElseThrow(() -> new AiWriteRefusedException("KI-Dienst " + question.service() + " ist nicht (mehr) eingerichtet"));
        var found = retrieval.retrieve(vaultId, question.askedBy(), service, consents.apply(vaultId), question.question(), questionVector);
        var sources = found.passages().stream()
            .map(passage -> new Question.Source(passage.n(), passage.noteId(), passage.heading(), excerpt(passage.text()))).toList();
        questions.saveSources(question.id(), sources, found.omittedForConsent());

        var sent = new LinkedHashMap<String, Object>();
        sent.put("questionId", question.id().toString());
        sent.put("service", service.id());
        sent.put("local", service.local());
        sent.put("notes", found.passages().stream().map(passage -> passage.noteId().value().toString()).distinct().toList());
        sent.put("omittedForConsent", found.omittedForConsent());
        audit.record(vaultId, null, question.askedBy(), "ai.ask", sent);
        return new Passages(found.passages(), service.local(), found.omittedForConsent());
    }

    /** Fuer den Worker: die Antwort. Eine Antwort ohne gueltiges Zitat wird nie gespeichert. */
    public void answer(UUID jobId, boolean answerable, String text) {
        var question = forJob(jobId);
        if (!answerable) {
            questions.saveAnswer(question.id(), false, null, List.of(), clock.get());
            return;
        }
        var answer = text == null ? "" : text.strip();
        if (answer.isEmpty() || answer.length() > MAX_ANSWER_LENGTH) {
            throw new AiWriteRefusedException("Die Antwort fehlt oder ist länger als " + MAX_ANSWER_LENGTH + " Zeichen");
        }
        var known = question.sources().stream().map(Question.Source::n).toList();
        var cited = new LinkedHashSet<Integer>();
        var matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            for (var number : matcher.group(1).split(",")) {
                cited.add(Integer.parseInt(number.strip()));
            }
        }
        if (cited.isEmpty() || !known.containsAll(cited)) {
            throw new AiWriteRefusedException("Die Antwort braucht mindestens ein Zitat und darf nur gesendete Stellen zitieren");
        }
        questions.saveAnswer(question.id(), true, answer, List.copyOf(cited), clock.get());
    }

    private Question forJob(UUID jobId) {
        var job = jobs.running(jobId);
        if (job.kind() != AiJob.Kind.ASK) {
            throw new AiJobGoneException();
        }
        return questions.findByJob(jobId).orElseThrow(AiJobGoneException::new);
    }

    private Question own(VaultId vaultId, String actor, UUID id) {
        return questions.find(vaultId, id).filter(question -> question.askedBy().equals(actor))
            .orElseThrow(() -> new AiWriteRefusedException("Frage nicht gefunden"));
    }

    private AiService defaultService() {
        var all = services.all();
        return all.stream().filter(AiService::local).findFirst().or(() -> all.stream().findFirst())
            .orElseThrow(() -> new AiWriteRefusedException("Es ist kein KI-Dienst eingerichtet"));
    }

    private View view(Question question, String actor) {
        var job = jobRepository.findById(question.jobId()).orElse(null);
        var citations = new ArrayList<Citation>();
        var byNumber = new LinkedHashMap<Integer, Question.Source>();
        question.sources().forEach(source -> byNumber.put(source.n(), source));
        var mayRead = access.accessChecker(question.vaultId(), actor);
        for (var number : question.cited()) {
            var source = byNumber.get(number);
            var note = source == null ? null : notes.findById(question.vaultId(), source.noteId()).orElse(null);
            if (note == null || !mayRead.apply(note.path()).allows(Permission.READ)) {
                return new View(withoutAnswer(question), job, List.of(), true);
            }
            citations.add(new Citation(number, note.id(), note.path(), source.heading(), source.excerpt()));
        }
        return new View(question, job, List.copyOf(citations), false);
    }

    private static Question withoutAnswer(Question question) {
        return new Question(question.id(), question.vaultId(), question.askedBy(), question.service(), question.question(),
            question.jobId(), question.askedAt(), question.answeredAt(), question.answered(), null, List.of(), List.of(),
            question.omittedForConsent());
    }

    private static String excerpt(String text) {
        var flat = text.strip().replaceAll("\\s+", " ");
        if (flat.length() <= EXCERPT_LENGTH) {
            return flat;
        }
        var cut = flat.lastIndexOf(' ', EXCERPT_LENGTH);
        return flat.substring(0, cut > EXCERPT_LENGTH / 2 ? cut : EXCERPT_LENGTH) + " …";
    }
}
