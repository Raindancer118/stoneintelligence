package de.raindancer118.stoneintelligence.platform.vault;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FolderPathsTest {

    @Test
    void should_acceptNormalFolders() {
        assertThat(List.of("Studium", "Studium/Mathe 1", "Ä ö/ü"))
            .allMatch(FolderPaths::isValid);
    }

    @Test
    void should_rejectWhatObsidianOrTheServerMustNotSee() {
        assertThat(List.of("", " A", "A/", "/A", "A//B", ".obsidian", "A/.trash", "../x", "A\\B", "A:B", "a\nb"))
            .noneMatch(FolderPaths::isValid);
    }

    @Test
    void should_listAllParentsOfANotePath() {
        assertThat(FolderPaths.parentsOf("A/B/C.md")).containsExactly("A", "A/B");
        assertThat(FolderPaths.parentsOf("C.md")).isEmpty();
    }
}
