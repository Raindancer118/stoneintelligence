package de.raindancer118.stoneai.extract;

import de.raindancer118.stoneai.chunk.Chunk;
import de.raindancer118.stoneai.config.StoneAiConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the per-chunk extraction: one cheap call per chunk, one repair round when the answer does
 * not hold up, and a hard stop once the run's token budget is spent.
 *
 * <p>Two decisions worth stating. A chunk that fails twice is <em>recorded</em>, never guessed
 * at — writing a note from an answer that broke its own contract is how wrong facts enter a
 * vault. And the budget is checked before each call rather than after, so a run degrades into
 * "did less" instead of "spent more than allowed".
 */
public final class ExtractionService {

    private final StoneAiConfig config;
    private final LlmClient llm;

    public ExtractionService(StoneAiConfig config, LlmClient llm) {
        this.config = config;
        this.llm = llm;
    }

    public ExtractionResult extract(List<Chunk> chunks) {
        List<ExtractedConcept> concepts = new ArrayList<>();
        List<ChunkFailure> failures = new ArrayList<>();
        String system = Prompts.extractionSystem(config.llm().language());
        int budget = config.llm().maxTokensPerRun();
        int used = 0;
        boolean exhausted = false;

        for (Chunk chunk : chunks) {
            if (used >= budget) {
                exhausted = true;
                break;
            }
            LlmAnswer answer = llm.complete(Tier.FAST, system, Prompts.extractionUser(chunk));
            used += answer.tokensUsed();

            try {
                concepts.addAll(ConceptJson.parse(answer.text(), chunk.provenance()));
                continue;
            } catch (ExtractionException first) {
                if (used >= budget) {
                    failures.add(failure(chunk, first.getMessage() + " (Budget vor Reparatur erschöpft)"));
                    exhausted = true;
                    break;
                }
                LlmAnswer repaired = llm.complete(Tier.FAST, system,
                        Prompts.repairUser(answer.text(), first.getMessage()));
                used += repaired.tokensUsed();
                try {
                    concepts.addAll(ConceptJson.parse(repaired.text(), chunk.provenance()));
                } catch (ExtractionException second) {
                    failures.add(failure(chunk, second.getMessage()));
                }
            }
        }

        return new ExtractionResult(concepts, failures, used, exhausted);
    }

    private static ChunkFailure failure(Chunk chunk, String reason) {
        return new ChunkFailure(chunk.index(), chunk.provenance().label(), reason);
    }
}
