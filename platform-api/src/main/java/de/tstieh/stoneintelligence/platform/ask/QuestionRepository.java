package de.tstieh.stoneintelligence.platform.ask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.VaultId;

public interface QuestionRepository {

    Question create(Question question);

    /** Vault-gescopt, wie die Jobs. */
    Optional<Question> find(VaultId vaultId, UUID id);

    Optional<Question> findByJob(UUID jobId);

    /** Die Fragen einer Person, neueste zuerst. */
    List<Question> listBy(VaultId vaultId, String askedBy, int limit);

    void saveSources(UUID id, List<Question.Source> sources, int omittedForConsent);

    void saveAnswer(UUID id, boolean answered, String answer, List<Integer> cited, Instant at);

    /** Nur die eigene Frage; {@code false}, wenn es sie nicht (mehr) gibt. */
    boolean delete(VaultId vaultId, String askedBy, UUID id);

    int purgeAskedBefore(Instant cutoff);
}
