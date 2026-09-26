package de.tstieh.stoneintelligence.worker.ingest;

import de.tstieh.stoneintelligence.stoneai.extract.LlmAnswer;
import de.tstieh.stoneintelligence.stoneai.extract.LlmClient;
import de.tstieh.stoneintelligence.stoneai.extract.Tier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkJudgeCheckTest {

    private static LlmClient answering(String json) {
        return new LlmClient() {
            @Override
            public LlmAnswer complete(Tier tier, String system, String user) {
                return new LlmAnswer(json, 1, "fake");
            }

            @Override
            public LlmAnswer readImage(byte[] png, String prompt) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void should_passOnlyWhenTheModelLinksTheFittingNoteAndNotTheOther() {
        assertThat(LinkJudgeCheck.check(answering("""
            {"links": [{"ziel": 1, "sinnvoll": true, "beziehung": "part_of", "anker": "Lichtreaktion"},
                       {"ziel": 2, "sinnvoll": false, "beziehung": "related_to", "anker": ""}]}"""))).isTrue();
        assertThat(LinkJudgeCheck.check(answering("""
            {"links": [{"ziel": 1, "sinnvoll": true}, {"ziel": 2, "sinnvoll": true}]}"""))).isFalse();
    }
}
