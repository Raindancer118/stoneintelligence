package de.tstieh.stoneintelligence.stoneai.extract;

import java.util.List;

/** Everything one extraction pass produced, including what went wrong. */
public record ExtractionResult(List<ExtractedConcept> concepts,
                               List<ChunkFailure> failures,
                               int tokensUsed,
                               boolean budgetExhausted,
                               List<String> unprocessed) {

    public ExtractionResult {
        concepts = List.copyOf(concepts);
        failures = List.copyOf(failures);
        unprocessed = List.copyOf(unprocessed);
    }
}
