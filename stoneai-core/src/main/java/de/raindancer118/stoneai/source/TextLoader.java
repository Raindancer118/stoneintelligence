package de.raindancer118.stoneai.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Markdown and plain text notes. Markdown frontmatter is stripped from the body but mined
 * for a title first — an existing note usually names itself better than its file name does.
 * Wikilinks, code fences and headings are left untouched, because they carry structure the
 * chunker and the extraction both rely on.
 */
public final class TextLoader implements DocumentLoader {

    private static final List<String> MARKDOWN = List.of(".md", ".markdown");
    private static final Pattern TITLE = Pattern.compile("^title:\\s*(.+)$", Pattern.MULTILINE);

    @Override
    public boolean supports(Path file) {
        String extension = Documents.extension(file);
        return MARKDOWN.contains(extension) || extension.equals(".txt");
    }

    @Override
    public SourceDocument load(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        boolean markdown = MARKDOWN.contains(Documents.extension(file));

        String frontmatter = markdown ? frontmatterOf(raw) : null;
        String body = frontmatter == null ? raw : raw.substring(frontmatter.length()).strip();

        if (body.isBlank()) {
            throw new EmptyDocumentException(file);
        }

        String title = Optional.ofNullable(frontmatter)
                .flatMap(TextLoader::titleIn)
                .orElseGet(() -> Documents.titleFromFileName(file));

        return new SourceDocument(file, title,
                markdown ? DocumentKind.MARKDOWN : DocumentKind.PLAIN_TEXT,
                List.of(new Page(1, body, false)), List.of(), false, Documents.sha256(file));
    }

    /** The frontmatter block including its closing delimiter, or {@code null} when there is none. */
    private static String frontmatterOf(String content) {
        if (!content.startsWith("---\n")) {
            return null;
        }
        int end = content.indexOf("\n---", 3);
        return end < 0 ? null : content.substring(0, Math.min(content.length(), end + 4));
    }

    private static Optional<String> titleIn(String frontmatter) {
        Matcher matcher = TITLE.matcher(frontmatter);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String value = matcher.group(1).trim().replaceAll("^[\"']|[\"']$", "");
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
