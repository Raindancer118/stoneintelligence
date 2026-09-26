package de.tstieh.stoneintelligence.worker.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.tstieh.stoneintelligence.stoneai.extract.LlmClient;
import de.tstieh.stoneintelligence.stoneai.extract.Tier;

/**
 * Stufe 3 der Verlinkung (ADR 0012): ein Sprachmodell entscheidet, ob inhaltlich aehnliche Notizen
 * wirklich verlinkt werden sollen, welche Art Beziehung das ist und an welchem Wort der Link haengt.
 * Ein Aufruf je Quellnotiz fuer alle ihre Kandidaten. Die Antwort wird streng geprueft: ein Anker
 * muss woertlich in der Quelle stehen (sonst kommt der Link unter "Verwandt"), unbekannte Beziehungen
 * werden zu "verwandt", und was nicht lesbar ist, gilt als keine Entscheidung - dann wird weder
 * verlinkt noch abgelehnt, und der naechste Lauf fragt erneut.
 */
public final class LinkJudge {

    public static final Set<String> RELATIONS = Set.of("uses", "requires", "part_of", "related_to", "described_by", "example_of",
        "contrasts_with");
    /** Laenge der Auszuege je Notiz - genug fuer den Zusammenhang, wenig genug fuer viele Kandidaten je Aufruf. */
    static final int EXCERPT = 1_200;

    public record Candidate(String targetNoteId, String title, String excerpt) {
    }

    /** {@code anchor == null}: kein woertlich passendes Wort - der Link kommt unter "Verwandt". */
    public record Verdict(String targetNoteId, boolean useful, String relation, String anchor) {
    }

    private static final String SYSTEM = """
        Du pruefst Verknuepfungen in einer persoenlichen Wissenssammlung (Obsidian). Eine Quellnotiz soll
        auf andere Notizen verlinken, aber nur, wenn der Link beim Lesen der Quelle wirklich weiterhilft:
        das Ziel erklaert einen Begriff der Quelle genauer, ist ein Teil davon, eine Voraussetzung, ein
        Beispiel oder ein bewusster Gegensatz. Nur thematisch Benachbartes ohne echten Bezug ist KEIN
        sinnvoller Link. Im Zweifel: nicht verlinken.

        Antworte ausschliesslich mit JSON in genau dieser Form:
        {"links": [{"ziel": <Nummer des Kandidaten>, "sinnvoll": true|false,
                    "beziehung": "uses"|"requires"|"part_of"|"related_to"|"described_by"|"example_of"|"contrasts_with",
                    "anker": "<Wort oder kurze Wortgruppe, WOERTLICH aus der Quelle kopiert, an der der Link haengen soll; leer, wenn keine passt>"}]}
        Jeder Kandidat genau einmal. Erfinde keine Inhalte und keinen Anker, der nicht woertlich in der Quelle steht.
        """;

    private final LlmClient llm;
    private final ObjectMapper json = new ObjectMapper();

    public LinkJudge(LlmClient llm) {
        this.llm = llm;
    }

    public List<Verdict> judge(String sourceTitle, String sourceText, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        var prompt = new StringBuilder("QUELLE: ").append(sourceTitle).append('\n')
            .append(excerpt(sourceText)).append("\n\nKANDIDATEN:\n");
        for (var i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            prompt.append(i + 1).append(". ").append(candidate.title()).append('\n').append(excerpt(candidate.excerpt())).append("\n\n");
        }
        var answer = llm.complete(Tier.SMART, SYSTEM, prompt.toString()).text();
        return parse(answer, sourceText, candidates);
    }

    private List<Verdict> parse(String answer, String sourceText, List<Candidate> candidates) {
        var start = answer == null ? -1 : answer.indexOf('{');
        var end = answer == null ? -1 : answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        JsonNode links;
        try {
            links = json.readTree(answer.substring(start, end + 1)).path("links");
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            return List.of();
        }
        var verdicts = new ArrayList<Verdict>();
        var seen = new java.util.HashSet<Integer>();
        for (var link : links) {
            var index = link.path("ziel").asInt(-1) - 1;
            if (index < 0 || index >= candidates.size() || !link.path("sinnvoll").isBoolean() || !seen.add(index)) {
                continue;
            }
            var relation = link.path("beziehung").asText("").strip().toLowerCase(java.util.Locale.ROOT);
            var anchor = link.path("anker").asText("").strip();
            verdicts.add(new Verdict(candidates.get(index).targetNoteId(), link.path("sinnvoll").asBoolean(),
                RELATIONS.contains(relation) ? relation : "related_to",
                !anchor.isEmpty() && sourceText.contains(anchor) ? anchor : null));
        }
        return verdicts;
    }

    private static String excerpt(String text) {
        var body = text == null ? "" : text.strip();
        return body.length() <= EXCERPT ? body : body.substring(0, EXCERPT) + " …";
    }
}
