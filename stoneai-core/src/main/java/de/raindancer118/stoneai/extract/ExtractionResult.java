package de.raindancer118.stoneai.extract;

import java.util.List;

/** Everything one extraction pass produced, including what went wrong. */
public record ExtractionResult(List<ExtractedConcept> concepts,
                               List<ChunkFailure> failures,
                               int tokensUsed,
                               boolean budgetExhausted) {

    public ExtractionResult {
        concepts = List.copyOf(concepts);
        failures = List.copyOf(failures);
    }
}
