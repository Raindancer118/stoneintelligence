package de.raindancer118.stoneai.vault;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoteFileNameTest {

    // Obsidian resolves [[Fluss im Wald]] by file name - "Fluss-im-Wald.md" would leave it dangling.
    @Test
    @DisplayName("should keep the spaces of a title, so a link by title finds the file")
    void should_keepSpaces() {
        assertThat(NoteFileName.forTitle("Fluss im Wald")).isEqualTo("Fluss im Wald.md");
    }

    @Test
    @DisplayName("should replace what Obsidian or a filesystem refuses")
    void should_replaceIllegalCharacters() {
        assertThat(NoteFileName.forTitle("Menge A/B: [Teil] #1")).isEqualTo("Menge A-B- -Teil- -1.md");
    }

    @Test
    @DisplayName("should turn typographic hyphens and spaces into plain ones")
    void should_normaliseTypographicCharacters() {
        assertThat(NoteFileName.forTitle("KFZ‑Meister Dello")).isEqualTo("KFZ-Meister Dello.md");
    }
}
