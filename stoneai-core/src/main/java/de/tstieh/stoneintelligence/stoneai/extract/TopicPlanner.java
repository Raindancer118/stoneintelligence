package de.tstieh.stoneintelligence.stoneai.extract;

import com.fasterxml.jackson.databind.JsonNode;
import de.tstieh.stoneintelligence.stoneai.chunk.Chunk;
import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;
import de.tstieh.stoneintelligence.stoneai.note.TextSimilarity;
import de.tstieh.stoneintelligence.stoneai.source.SourceDocument;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which notes a document should become - before any chunk is read on its own. Reading
 * chunk by chunk, a model sees only terms; asked about the whole document it sees what the
 * document is about: an accident with its parties, a character and her story, the central ideas
 * of a lecture. The plan also carries the titles the vault already has, so a second letter about
 * the same accident extends the note of the first.
 */
public final class TopicPlanner {

    /** How much of the document the planner reads - plenty for the gist, bounded in cost. */
    static final int TEXT_BUDGET = 24_000;
    /** At most this much outline; a longer one keeps an even sample of its entries, and the last. */
    static final int OUTLINE_BUDGET = 12_000;
    private static final int MIN_EXCERPT_BUDGET = 12_000;
    /** Above this many notes, only titles sharing a word with the document are offered. */
    private static final int MAX_EXISTING = 200;

    private final StoneAiConfig config;
    private final LlmClient llm;

    public TopicPlanner(StoneAiConfig config, LlmClient llm) {
        this.config = config;
        this.llm = llm;
    }

    /**
     * The plan and what it cost. An answer that stays unusable after one repair round falls back
     * to one note about the document; a provider that cannot be reached is not papered over - the
     * exception travels up, so the job is retried instead of producing a worse result.
     */
    public Result plan(SourceDocument document, List<Chunk> chunks, List<String> existingTitles) {
        String system = Prompts.planSystem(config.llm().language());
        boolean complete = chunks.stream().mapToInt(chunk -> chunk.text().length()).sum() <= TEXT_BUDGET;
        // A long document is read in excerpts - its outline shows the planner every chapter anyway.
        String outline = complete ? "" : outline(document);
        String user = Prompts.planUser(document.title(), relevant(existingTitles, document), outline,
                excerpt(chunks, Math.max(MIN_EXCERPT_BUDGET, TEXT_BUDGET - outline.length())));
        LlmAnswer answer = llm.complete(Tier.SMART, system, user);
        try {
            return new Result(parse(answer.text(), complete, document), answer.tokensUsed());
        } catch (ExtractionException first) {
            LlmAnswer repaired = llm.complete(Tier.SMART, system, Prompts.repairUser(answer.text(), first.getMessage()));
            int used = answer.tokensUsed() + repaired.tokensUsed();
            try {
                return new Result(parse(repaired.text(), complete, document), used);
            } catch (ExtractionException second) {
                return new Result(TopicPlan.single(document.title()), used);
            }
        }
    }

    public record Result(TopicPlan plan, int tokensUsed) {
    }

    private TopicPlan parse(String answer, boolean complete, SourceDocument document) {
        JsonNode topics = ConceptJson.tree(answer == null ? "" : answer).get("topics");
        if (topics == null || !topics.isArray()) {
            throw new ExtractionException("the answer has no \"topics\" array");
        }
        List<TopicPlan.Topic> planned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : topics) {
            String title = TopicPlan.withoutKind(TextSimilarity.plain(node.path("title").asText("")));
            if (title.isEmpty() || !seen.add(TextSimilarity.normalise(title))) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            node.path("aliases").forEach(alias -> {
                String plain = TextSimilarity.plain(alias.asText(""));
                if (!plain.isEmpty()) {
                    aliases.add(plain);
                }
            });
            planned.add(new TopicPlan.Topic(title, node.path("kind").asText("thema").strip(),
                    node.path("scope").asText("").strip(), aliases));
        }
        if (planned.isEmpty()) {
            throw new ExtractionException("the plan names no topic");
        }
        int limit = Math.max(1, config.notes().maxNotesFor(document.lengthInPages()));
        return new TopicPlan(planned.size() > limit ? planned.subList(0, limit) : planned, complete);
    }

    static String outline(SourceDocument document) {
        List<String> entries = de.tstieh.stoneintelligence.stoneai.source.PageCleaner.outline(document);
        int total = entries.stream().mapToInt(entry -> entry.length() + 1).sum();
        if (total <= OUTLINE_BUDGET) {
            return String.join("\n", entries);
        }
        int keep = Math.max(2, entries.size() * OUTLINE_BUDGET / total);
        List<String> sampled = new ArrayList<>();
        for (int i = 0; i < keep; i++) {
            sampled.add(entries.get((int) ((long) i * (entries.size() - 1) / (keep - 1))));
        }
        return String.join("\n", sampled);
    }

    /** Shortest useful piece of a chunk: shorter than this, an excerpt says nothing about it. */
    private static final int MIN_SHARE = 400;

    /**
     * The document, or - when it is long - the beginning of every chunk, so no part is unseen.
     * A book with more chunks than the budget has room for gets an even sample from the first to
     * the last chunk instead: cut off after the first few dozen, the planner would only know how
     * the book begins.
     */
    static String excerpt(List<Chunk> chunks, int budget) {
        int total = chunks.stream().mapToInt(chunk -> chunk.text().length()).sum();
        if (total <= budget) {
            return String.join("\n\n", chunks.stream().map(Chunk::text).toList());
        }
        int labels = chunks.stream().mapToInt(chunk -> chunk.provenance().label().length() + 8).sum() / chunks.size();
        int room = Math.max(2, budget / (MIN_SHARE + labels));
        List<Chunk> shown = chunks.size() <= room ? chunks : evenly(chunks, room);
        int share = Math.max(MIN_SHARE, budget / shown.size() - labels);
        StringBuilder excerpt = new StringBuilder();
        for (Chunk chunk : shown) {
            String text = chunk.text();
            excerpt.append("[").append(chunk.provenance().label()).append("]\n")
                    .append(text, 0, Math.min(share, text.length())).append(text.length() > share ? " …" : "")
                    .append("\n\n");
        }
        return excerpt.toString();
    }

    /** {@code count} chunks spread evenly over all of them, the first and the last included. */
    private static List<Chunk> evenly(List<Chunk> chunks, int count) {
        List<Chunk> sampled = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sampled.add(chunks.get((int) ((long) i * (chunks.size() - 1) / (count - 1))));
        }
        return sampled;
    }

    /** In a large vault, the titles that share a word with the document - the rest cannot match. */
    private static List<String> relevant(List<String> titles, SourceDocument document) {
        List<String> distinct = titles.stream().distinct().toList();
        if (distinct.size() <= MAX_EXISTING) {
            return distinct;
        }
        Set<String> words = new LinkedHashSet<>(Arrays.asList(TextSimilarity.normalise(document.fullText()).split(" ")));
        return distinct.stream()
                .filter(title -> Arrays.stream(TextSimilarity.normalise(title).split(" "))
                        .anyMatch(word -> word.length() >= 4 && words.contains(word)))
                .limit(MAX_EXISTING)
                .toList();
    }
}
