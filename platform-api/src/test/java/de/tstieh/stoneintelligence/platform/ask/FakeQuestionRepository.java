package de.tstieh.stoneintelligence.platform.ask;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public final class FakeQuestionRepository implements QuestionRepository {

    private final Map<UUID, Question> questions = new LinkedHashMap<>();

    @Override
    public synchronized Question create(Question question) {
        questions.put(question.id(), question);
        return question;
    }

    @Override
    public synchronized Optional<Question> find(VaultId vaultId, UUID id) {
        return Optional.ofNullable(questions.get(id)).filter(question -> question.vaultId().equals(vaultId));
    }

    @Override
    public synchronized Optional<Question> findByJob(UUID jobId) {
        return questions.values().stream().filter(question -> question.jobId().equals(jobId)).findFirst();
    }

    @Override
    public synchronized List<Question> listBy(VaultId vaultId, String askedBy, int limit) {
        return questions.values().stream().filter(q -> q.vaultId().equals(vaultId) && q.askedBy().equals(askedBy))
            .sorted(Comparator.comparing(Question::askedAt).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized void saveSources(UUID id, List<Question.Source> sources, int omittedForConsent) {
        var q = questions.get(id);
        questions.put(id, new Question(q.id(), q.vaultId(), q.askedBy(), q.service(), q.question(), q.jobId(), q.askedAt(),
            q.answeredAt(), q.answered(), q.answer(), sources, q.cited(), omittedForConsent));
    }

    @Override
    public synchronized void saveAnswer(UUID id, boolean answered, String answer, List<Integer> cited, Instant at) {
        var q = questions.get(id);
        questions.put(id, new Question(q.id(), q.vaultId(), q.askedBy(), q.service(), q.question(), q.jobId(), q.askedAt(),
            at, answered, answer, q.sources(), cited, q.omittedForConsent()));
    }

    @Override
    public synchronized boolean delete(VaultId vaultId, String askedBy, UUID id) {
        var q = questions.get(id);
        if (q == null || !q.vaultId().equals(vaultId) || !q.askedBy().equals(askedBy)) {
            return false;
        }
        questions.remove(id);
        return true;
    }

    @Override
    public synchronized int purgeAskedBefore(Instant cutoff) {
        var old = questions.values().stream().filter(q -> q.askedAt().isBefore(cutoff)).map(Question::id).toList();
        old.forEach(questions::remove);
        return old.size();
    }
}
