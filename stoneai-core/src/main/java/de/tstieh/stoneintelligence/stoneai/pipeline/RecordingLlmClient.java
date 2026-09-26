package de.tstieh.stoneintelligence.stoneai.pipeline;

import de.tstieh.stoneintelligence.stoneai.extract.LlmAnswer;
import de.tstieh.stoneintelligence.stoneai.extract.LlmClient;
import de.tstieh.stoneintelligence.stoneai.extract.Tier;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Wraps a client and remembers which model actually answered. That list goes into the ledger:
 * knowing which external service a document's contents were sent to is not a nice-to-have once a
 * file turns out to have contained someone's personal data.
 */
final class RecordingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final Set<String> models = new LinkedHashSet<>();

    RecordingLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    List<String> models() {
        return List.copyOf(models);
    }

    @Override
    public LlmAnswer complete(Tier tier, String system, String user) {
        return remember(delegate.complete(tier, system, user));
    }

    @Override
    public LlmAnswer readImage(byte[] pngImage, String prompt) {
        return remember(delegate.readImage(pngImage, prompt));
    }

    private LlmAnswer remember(LlmAnswer answer) {
        if (answer.model() != null && !answer.model().isBlank()) {
            models.add(answer.model());
        }
        return answer;
    }
}
