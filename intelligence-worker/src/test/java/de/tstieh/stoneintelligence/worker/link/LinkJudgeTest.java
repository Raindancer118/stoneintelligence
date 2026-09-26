package de.tstieh.stoneintelligence.worker.link;

import java.util.ArrayList;
import java.util.List;
import de.tstieh.stoneintelligence.stoneai.extract.LlmAnswer;
import de.tstieh.stoneintelligence.stoneai.extract.LlmClient;
import de.tstieh.stoneintelligence.stoneai.extract.Tier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkJudgeTest {

    private final List<String> prompts = new ArrayList<>();

    private LlmClient answering(String json) {
        return new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                prompts.add(tier + "|" + system + "|" + user);
                return new LlmAnswer(json, 100, "fake");
            }

            @Override
            public LlmAnswer readImage(byte[] pngImage, String prompt) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final String SOURCE = "Chlorophyll ist der grüne Farbstoff und treibt die Lichtreaktion an.";
    private static final List<LinkJudge.Candidate> CANDIDATES = List.of(
        new LinkJudge.Candidate("n1", "Photosynthese", "Pflanzen wandeln Licht in Energie um."),
        new LinkJudge.Candidate("n2", "Umsatzsteuer", "Steuer auf Umsätze."));

    @Test
    void should_askOnceForAllCandidates_andReadTheVerdicts() {
        var judge = new LinkJudge(answering("""
            Hier das Ergebnis:
            {"links": [
              {"ziel": 1, "sinnvoll": true, "beziehung": "part_of", "anker": "Lichtreaktion"},
              {"ziel": 2, "sinnvoll": false, "beziehung": "related_to", "anker": ""}
            ]}
            """));

        var verdicts = judge.judge("Chlorophyll", SOURCE, CANDIDATES);

        assertThat(prompts).hasSize(1).first().asString().startsWith("SMART|").contains("Photosynthese").contains("Umsatzsteuer");
        assertThat(verdicts).containsExactly(
            new LinkJudge.Verdict("n1", true, "part_of", "Lichtreaktion"),
            new LinkJudge.Verdict("n2", false, "related_to", null));
    }

    @Test
    void should_dropAnchorsThatAreNotQuotedExactly_andUnknownRelations() {
        var judge = new LinkJudge(answering("""
            {"links": [{"ziel": 1, "sinnvoll": true, "beziehung": "verwandt irgendwie", "anker": "Lichtreaktionen im Blatt"}]}
            """));

        assertThat(judge.judge("Chlorophyll", SOURCE, CANDIDATES)).containsExactly(new LinkJudge.Verdict("n1", true, "related_to", null));
    }

    @Test
    void should_treatAnUnreadableAnswer_asNoDecision() {
        assertThat(new LinkJudge(answering("Das kann ich nicht beurteilen.")).judge("Chlorophyll", SOURCE, CANDIDATES)).isEmpty();
        assertThat(new LinkJudge(answering("{\"links\": [{\"ziel\": 9, \"sinnvoll\": true}]}")).judge("Chlorophyll", SOURCE, CANDIDATES))
            .isEmpty();
    }
}
