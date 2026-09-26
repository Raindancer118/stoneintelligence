package de.tstieh.stoneintelligence.stoneai.extract;

import de.tstieh.stoneintelligence.stoneai.chunk.Chunk;
import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import de.tstieh.stoneintelligence.stoneai.note.TextSimilarity;
import de.tstieh.stoneintelligence.stoneai.pipeline.BoundedParallel;
import de.tstieh.stoneintelligence.stoneai.pipeline.ProgressSink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the per-chunk extraction: one cheap call per chunk, several chunks at once, one repair
 * round when the answer does not hold up, and a hard stop once the run's token budget is spent.
 *
 * <p>Two decisions worth stating. A chunk that fails twice is <em>recorded</em>, never guessed
 * at — writing a note from an answer that broke its own contract is how wrong facts enter a
 * vault. And the budget is checked before each call rather than after, so a run degrades into
 * "did less" instead of "spent more than allowed".
 */
public final class ExtractionService {

    private final StoneAiConfig config;
    private final LlmClient llm;
    private final ProgressSink progress;

    public ExtractionService(StoneAiConfig config, LlmClient llm) {
        this(config, llm, ProgressSink.NONE);
    }

    public ExtractionService(StoneAiConfig config, LlmClient llm, ProgressSink progress) {
        this.config = config;
        this.llm = llm;
        this.progress = progress;
    }

    public ExtractionResult extract(List<Chunk> chunks) {
        return extract(chunks, TopicPlan.none());
    }

    /** Extracts the planned topics within {@code llm.maxTokensPerRun}; see {@link #extract(List, TopicPlan, int)}. */
    public ExtractionResult extract(List<Chunk> chunks, TopicPlan plan) {
        return extract(chunks, plan, config.llm().maxTokensPerRun());
    }

    /**
     * Extracts the planned topics. When the planner read the whole document, a
     * note outside the plan is a detail the model split off after all - it is folded into the
     * topic it resembles, or else the main topic. Only in a long document, where the planner saw
     * excerpts, may a chunk add a topic of its own.
     *
     * <p>Up to {@code llm.parallelCalls} chunks are read at once. The budget is checked before
     * each chunk starts, so a run may overshoot it by the calls already under way - never by more.
     */
    public ExtractionResult extract(List<Chunk> chunks, TopicPlan plan, int budget) {
        boolean mayAddTopics = plan.isEmpty() || !plan.complete();
        String system = Prompts.extractionSystem(config.llm().language(), mayAddTopics);
        AtomicInteger used = new AtomicInteger();
        int[] done = {0};

        progress.report("Abschnitt 1/" + chunks.size() + " wird gelesen", 0);
        List<Outcome> outcomes = BoundedParallel.run(chunks.size(), config.llm().parallelCalls(),
                index -> read(chunks.get(index), plan, mayAddTopics, system, used, budget),
                index -> used.get() < budget,
                (index, outcome) -> {
                    done[0]++;
                    progress.report("Abschnitt " + done[0] + "/" + chunks.size() + " gelesen", done[0] * 100 / chunks.size());
                });
        progress.report("Abschnitte gelesen", 100);

        List<ExtractedConcept> concepts = new ArrayList<>();
        List<ChunkFailure> failures = new ArrayList<>();
        List<String> unprocessed = new ArrayList<>();
        boolean exhausted = false;
        for (int index = 0; index < chunks.size(); index++) {
            Outcome outcome = outcomes.get(index);
            if (outcome == null) {
                // Named, not just counted: the source note tells the reader what is missing.
                unprocessed.add(chunks.get(index).provenance().label());
                exhausted = true;
                continue;
            }
            concepts.addAll(outcome.concepts());
            if (outcome.failure() != null) {
                failures.add(outcome.failure());
            }
            exhausted |= outcome.budgetStopped();
        }
        return new ExtractionResult(concepts, failures, used.get(), exhausted, unprocessed.stream().distinct().toList());
    }

    private record Outcome(List<ExtractedConcept> concepts, ChunkFailure failure, boolean budgetStopped) {
    }

    /** One chunk: the call, and one repair round if the answer does not hold up. */
    private Outcome read(Chunk chunk, TopicPlan plan, boolean mayAddTopics, String system, AtomicInteger used, int budget) {
        LlmAnswer answer = llm.complete(Tier.FAST, system, Prompts.extractionUser(chunk, plan));
        used.addAndGet(answer.tokensUsed());
        try {
            return new Outcome(fitted(ConceptJson.parse(answer.text(), chunk.provenance()), plan, mayAddTopics), null, false);
        } catch (ExtractionException first) {
            if (used.get() >= budget) {
                return new Outcome(List.of(), failure(chunk, first.getMessage() + " (Budget vor Reparatur erschöpft)"), true);
            }
            LlmAnswer repaired = llm.complete(Tier.FAST, system, Prompts.repairUser(answer.text(), first.getMessage()));
            used.addAndGet(repaired.tokensUsed());
            try {
                return new Outcome(fitted(ConceptJson.parse(repaired.text(), chunk.provenance()), plan, mayAddTopics), null, false);
            } catch (ExtractionException second) {
                return new Outcome(List.of(), failure(chunk, second.getMessage()), false);
            }
        }
    }

    private List<ExtractedConcept> fitted(List<ExtractedConcept> parsed, TopicPlan plan, boolean mayAddTopics) {
        if (plan.isEmpty()) {
            return parsed;
        }
        double threshold = config.notes().similarityThreshold();
        List<ExtractedConcept> fitted = new ArrayList<>();
        for (ExtractedConcept raw : parsed) {
            ExtractedConcept concept = new ExtractedConcept(TopicPlan.withoutKind(raw.title()), raw.aliases(), raw.definition(),
                    raw.body(), raw.tags(), raw.entities(), raw.related().stream().map(TopicPlan::withoutKind).toList(),
                    raw.confidence(), raw.provenance());
            TopicPlan.Topic topic = plan.topics().stream()
                    .filter(candidate -> matches(candidate, concept, threshold))
                    .findFirst().orElse(null);
            if (topic != null) {
                fitted.add(retitled(concept, topic, concept.definition(), concept.confidence()));
            } else if (mayAddTopics) {
                fitted.add(concept);
            } else {
                // A detail the plan already covers: its text joins the main topic, but it never
                // decides that note's definition.
                fitted.add(retitled(withHeading(concept), plan.main(), "", 0.0));
            }
        }
        return fitted;
    }

    private static ExtractedConcept withHeading(ExtractedConcept concept) {
        return new ExtractedConcept(concept.title(), concept.aliases(), concept.definition(),
                "### " + concept.title() + "\n\n" + concept.body(), concept.tags(), concept.entities(),
                concept.related(), concept.confidence(), concept.provenance());
    }

    private static boolean matches(TopicPlan.Topic topic, ExtractedConcept concept, double threshold) {
        List<String> names = new ArrayList<>(topic.aliases());
        names.add(topic.title());
        return names.stream().anyMatch(name -> TextSimilarity.sameConcept(name, concept.title(), threshold)
                || concept.aliases().stream().anyMatch(alias -> TextSimilarity.sameConcept(name, alias, threshold)));
    }

    private static ExtractedConcept retitled(ExtractedConcept concept, TopicPlan.Topic topic,
                                             String definition, double confidence) {
        List<String> aliases = new ArrayList<>(topic.aliases());
        if (confidence > 0) {
            concept.aliases().stream().filter(alias -> !aliases.contains(alias)).forEach(aliases::add);
        }
        return new ExtractedConcept(topic.title(), aliases, definition, concept.body(), concept.tags(),
                concept.entities(), concept.related(), confidence, concept.provenance());
    }

    private static ChunkFailure failure(Chunk chunk, String reason) {
        return new ChunkFailure(chunk.index(), chunk.provenance().label(), reason);
    }
}
