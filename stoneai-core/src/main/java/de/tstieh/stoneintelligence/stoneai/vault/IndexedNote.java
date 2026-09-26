package de.tstieh.stoneintelligence.stoneai.vault;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** What the index needs to know about an existing note. */
public record IndexedNote(Path file, String title, List<String> aliases) {

    public IndexedNote {
        aliases = List.copyOf(aliases);
    }

    /** Title from the frontmatter, else the file name; aliases from the frontmatter. */
    public static IndexedNote fromContent(Path file, String content) {
        Frontmatter frontmatter = Frontmatter.of(content).frontmatter();
        String title = Optional.ofNullable(frontmatter.scalar("title"))
                .filter(value -> !value.isBlank())
                .orElseGet(() -> titleOf(file));
        return new IndexedNote(file, title, frontmatter.list("aliases"));
    }

    public static String titleOf(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".md") ? name.substring(0, name.length() - ".md".length()) : name;
    }
}
