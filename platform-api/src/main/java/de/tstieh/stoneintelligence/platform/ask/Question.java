package de.tstieh.stoneintelligence.platform.ask;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

/**
 * Eine Frage an den Vault (ADR 0017). {@code sources} sind die an den KI-Dienst gesendeten Stellen, {@code cited}
 * die Nummern, die die Antwort zitiert. {@code answered == null}: noch keine Antwort; {@code false}: die Notizen
 * beantworten die Frage nicht.
 */
public record Question(UUID id, VaultId vaultId, String askedBy, String service, String question, UUID jobId, Instant askedAt,
                       Instant answeredAt, Boolean answered, String answer, List<Source> sources, List<Integer> cited,
                       int omittedForConsent) {

    /** Eine gesendete Stelle: die Nummer, unter der die Antwort sie zitiert, ihre Notiz, Ueberschrift und ein kurzer Auszug. */
    public record Source(int n, NoteId noteId, String heading, String excerpt) { }

    public Question {
        sources = List.copyOf(sources);
        cited = List.copyOf(cited);
    }

    public static Question asked(VaultId vaultId, String askedBy, String service, String question, UUID jobId, Instant at) {
        return new Question(UUID.randomUUID(), vaultId, askedBy, service, question, jobId, at, null, null, null, List.of(), List.of(), 0);
    }
}
