package de.raindancer118.stoneai.note;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Models write LaTeX as \( … \) and \[ … \]; Obsidian only renders $ … $ and $$ … $$. */
class MathDelimitersTest {

    @Test
    @DisplayName("should turn inline and display LaTeX into Obsidian's dollar form")
    void should_convertDelimiters() {
        assertThat(MathDelimiters.forObsidian("Sei \\(R\\subseteq M\\times M\\) reflexiv:\n\\[\\forall x: xRx\\]"))
                .isEqualTo("Sei $R\\subseteq M\\times M$ reflexiv:\n$$\\forall x: xRx$$");
    }

    @Test
    @DisplayName("should leave code alone")
    void should_notTouchCode() {
        String code = "```java\nString s = \"\\\\(x\\\\)\";\n```\nund `\\(y\\)` im Text";

        assertThat(MathDelimiters.forObsidian(code)).isEqualTo(code);
    }

    @Test
    @DisplayName("should trim the spaces Obsidian does not accept inside inline math")
    void should_trimInlineMath() {
        assertThat(MathDelimiters.forObsidian("\\( x + 1 \\)")).isEqualTo("$x + 1$");
    }
}
