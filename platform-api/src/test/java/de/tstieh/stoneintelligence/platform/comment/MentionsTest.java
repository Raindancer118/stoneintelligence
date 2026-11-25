package de.tstieh.stoneintelligence.platform.comment;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class MentionsTest {

    @Test
    void should_findEachPersonOnce_inTextOrder() {
        var found = Mentions.of("@[Ben Berg](ben) und @[Cara](cara), nochmal @[Ben Berg](ben)");

        assertThat(found).containsExactly(java.util.Map.entry("ben", "Ben Berg"), java.util.Map.entry("cara", "Cara"));
    }

    @Test
    void should_ignoreBrokenOrLookalikeTokens() {
        assertThat(Mentions.of("@ben, [Ben](ben), @[Ben]( ben), @[](x), mail a@[b](c d)")).isEmpty();
    }

    @Test
    void should_renderPlainTextForPreviews() {
        assertThat(Mentions.plain("Hi @[Ben $1](ben)!")).isEqualTo("Hi @Ben $1!");
    }

    @Test
    void should_answerRejectedMentionsWith422AndTheReadableMessage() {
        var problem = new CommentController(null).rejected(new CommentRejectedException("Ben darf das nicht lesen."));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(problem.getDetail()).isEqualTo("Ben darf das nicht lesen.");
    }
}
