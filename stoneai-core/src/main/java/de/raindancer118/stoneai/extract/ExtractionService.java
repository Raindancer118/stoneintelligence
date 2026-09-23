package de.raindancer118.stoneai.extract;

import de.raindancer118.stoneai.chunk.Chunk;
import de.raindancer118.stoneai.config.StoneAiConfig;
import de.raindancer118.stoneai.note.TextSimilarity;

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
        return extract(chunks, TopicPlan.none());
    }

    /**
     * Extracts the planned topics. When the planner read the whole document, a
     * note outside the plan is a detail the model split off after all - it is folded into the
     * topic it resembles, or else the main topic. Only in a long document, where the planner saw
     * excerpts, may a chunk add a topic of its own.
     */
    public ExtractionResult extract(List<Chunk> chunks, TopicPlan plan) {
        List<ExtractedConcept> concepts = new ArrayList<>();
        List<ChunkFailure> failures = new ArrayList<>();
        boolean mayAddTopics = plan.isEmpty() || !plan.complete();
        String system = Prompts.extractionSystem(config.llm().language(), mayAddTopics);
        int budget = config.llm().maxTokensPerRun();
        int used = 0;
        boolean exhausted = false;

        List<String> unprocessed = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            Chunk chunk = chunks.get(index);
            if (used >= budget) {
                exhausted = true;
                // Named, not just counted: the source note tells the reader what is missing.
                chunks.subList(index, chunks.size()).forEach(rest -> unprocessed.add(rest.provenance().label()));
                break;
            }
            LlmAnswer answer = llm.complete(Tier.FAST, system, Prompts.extractionUser(chunk, plan));
            used += answer.tokensUsed();

            try {
                concepts.addAll(fitted(ConceptJson.parse(answer.text(), chunk.provenance()), plan, mayAddTopics));
                continue;
            } catch (ExtractionException first) {
                if (used >= budget) {
                    failures.add(failure(chunk, first.getMessage() + " (Budget vor Reparatur erschöpft)"));
                    exhausted = true;
                    chunks.subList(index + 1, chunks.size()).forEach(rest -> unprocessed.add(rest.provenance().label()));
                    break;
                }
                LlmAnswer repaired = llm.complete(Tier.FAST, system,
                        Prompts.repairUser(answer.text(), first.getMessage()));
                used += repaired.tokensUsed();
                try {
                    concepts.addAll(fitted(ConceptJson.parse(repaired.text(), chunk.provenance()), plan, mayAddTopics));
                } catch (ExtractionException second) {
                    failures.add(failure(chunk, second.getMessage()));
                }
            }
        }

        return new ExtractionResult(concepts, failures, used, exhausted, unprocessed.stream().distinct().toList());
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
