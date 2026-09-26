package de.tstieh.stoneintelligence.stoneai.protection;

import de.tstieh.stoneintelligence.stoneai.config.StoneAiConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides what the AI is not allowed to see. Four independent ways to protect something, any one
 * of which is enough:
 *
 * <ol>
 *   <li>a marker in the file or folder name, e.g. {@code Steuer [noai].pdf};</li>
 *   <li>a {@code .stoneaiignore} file with glob patterns, valid from its folder downwards;</li>
 *   <li>the protection tag inside a note — in the frontmatter {@code tags} or inline as
 *       {@code #NoStoneAI};</li>
 *   <li>the protection tag in a PDF's metadata (keywords, subject or title).</li>
 * </ol>
 *
 * <p>Protection is checked before a document is read into memory, and again before an existing
 * note is touched, so a protected file is neither sent to a provider nor modified.
 */
public final class ProtectionPolicy {

    private static final int METADATA_SCAN_LIMIT = 64 * 1024;
    private static final List<String> TEXT_SUFFIXES = List.of(".md", ".markdown", ".txt");

    private final Path root;
    private final String tag;
    private final String ignoreFileName;
    private final List<String> markers;
    private final List<PathMatcher> excludeGlobs;
    private final boolean respectPdfKeywords;
    private final Pattern inlineTag;
    private final Map<Path, IgnoreRules> ignoreCache = new ConcurrentHashMap<>();

    private ProtectionPolicy(StoneAiConfig config, Path root) {
        this.root = root.toAbsolutePath().normalize();
        StoneAiConfig.Protection protection = config.protection();
        this.tag = protection.excludeTag();
        this.ignoreFileName = protection.ignoreFileName();
        this.markers = protection.filenameMarkers().stream()
                .map(marker -> marker.toLowerCase(Locale.ROOT))
                .toList();
        this.excludeGlobs = protection.excludeGlobs().stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                .toList();
        this.respectPdfKeywords = protection.respectPdfKeywords();
        this.inlineTag = Pattern.compile("(?<![\\w/])#" + Pattern.quote(tag) + "(?![\\w/-])",
                Pattern.CASE_INSENSITIVE);
    }

    public static ProtectionPolicy of(StoneAiConfig config, Path root) {
        return new ProtectionPolicy(config, root);
    }

    public String tag() {
        return tag;
    }

    /** Full check for a file on disk, including its content. */
    public ProtectionDecision inspect(Path file) {
        ProtectionDecision byPath = inspectPath(file);
        if (byPath.isProtected()) {
            return byPath;
        }
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            return ProtectionDecision.allowed();
        }
        String name = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
        if (TEXT_SUFFIXES.stream().anyMatch(name::endsWith)) {
            return inspectText(readHead(absolute));
        }
        if (respectPdfKeywords && name.endsWith(".pdf")) {
            return PdfMetadataProtection.inspect(absolute, tag);
        }
        return ProtectionDecision.allowed();
    }

    /** The cheap part of the check: everything that follows from the path alone. */
    public ProtectionDecision inspectPath(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (absolute.getFileName() != null && absolute.getFileName().toString().equals(ignoreFileName)) {
            return ProtectionDecision.protectedBy("ist die Ignore-Datei " + ignoreFileName);
        }

        Path relative = relativize(absolute);
        if (relative != null) {
            for (PathMatcher glob : excludeGlobs) {
                if (glob.matches(relative)) {
                    return ProtectionDecision.protectedBy("Pfad ist per protection.excludeGlobs ausgeschlossen");
                }
            }
        }

        ProtectionDecision byMarker = inspectNameMarkers(absolute, relative);
        if (byMarker.isProtected()) {
            return byMarker;
        }
        return inspectIgnoreFiles(absolute);
    }

    /** Whether a note's text protects it — used before appending to an existing note. */
    public ProtectionDecision inspectText(String content) {
        String frontmatterTags = frontmatterTags(content);
        if (frontmatterTags != null && containsTag(frontmatterTags)) {
            return ProtectionDecision.protectedBy("Frontmatter enthält das Schutz-Tag " + tag);
        }
        if (inlineTag.matcher(content).find()) {
            return ProtectionDecision.protectedBy("Dokument enthält das Schutz-Tag #" + tag);
        }
        return ProtectionDecision.allowed();
    }

    private ProtectionDecision inspectNameMarkers(Path absolute, Path relative) {
        if (markers.isEmpty()) {
            return ProtectionDecision.allowed();
        }
        Path segments = relative != null ? relative : absolute.getFileName();
        for (Path segment : segments) {
            String lower = segment.toString().toLowerCase(Locale.ROOT);
            for (String marker : markers) {
                if (lower.contains(marker)) {
                    return ProtectionDecision.protectedBy(
                            "Name '" + segment + "' enthält den Schutz-Marker " + marker);
                }
            }
        }
        return ProtectionDecision.allowed();
    }

    private ProtectionDecision inspectIgnoreFiles(Path absolute) {
        Boolean verdict = null;
        Path directory = absolute.getParent();
        List<Path> chain = new ArrayList<>();
        while (directory != null && directory.startsWith(root)) {
            chain.add(directory);
            directory = directory.getParent();
        }
        // Outermost first, so a nested ignore file can override the one above it.
        for (int i = chain.size() - 1; i >= 0; i--) {
            Path ignoreFile = chain.get(i).resolve(ignoreFileName);
            if (!Files.isRegularFile(ignoreFile)) {
                continue;
            }
            IgnoreRules rules = ignoreCache.computeIfAbsent(ignoreFile, IgnoreRules::read);
            Boolean match = rules.matches(absolute);
            if (match != null) {
                verdict = match;
            }
        }
        return Boolean.TRUE.equals(verdict)
                ? ProtectionDecision.protectedBy("passt auf ein Muster in " + ignoreFileName)
                : ProtectionDecision.allowed();
    }

    private boolean containsTag(String haystack) {
        for (String candidate : haystack.split("[,\\[\\]\\s]+")) {
            String cleaned = candidate.trim();
            if (cleaned.startsWith("#")) {
                cleaned = cleaned.substring(1);
            }
            if (cleaned.equalsIgnoreCase(tag)) {
                return true;
            }
        }
        return false;
    }

    /** The raw text of the frontmatter {@code tags} entry, or {@code null} when there is none. */
    private static String frontmatterTags(String content) {
        if (!content.startsWith("---")) {
            return null;
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            return null;
        }
        String frontmatter = content.substring(0, end);
        Matcher matcher = Pattern.compile("^tags:\\s*(.*(?:\\n\\s+-.*)*)$",
                Pattern.MULTILINE).matcher(frontmatter);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Path relativize(Path absolute) {
        return absolute.startsWith(root) ? root.relativize(absolute) : null;
    }

    private static String readHead(Path file) {
        try {
            long size = Files.size(file);
            if (size <= METADATA_SCAN_LIMIT) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
            byte[] head = new byte[METADATA_SCAN_LIMIT];
            try (var in = Files.newInputStream(file)) {
                int read = in.readNBytes(head, 0, head.length);
                return new String(head, 0, read, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
