package de.raindancer118.stoneai.protection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * The rules of one {@code .stoneaiignore} file. Patterns are gitignore-flavoured: relative to the
 * folder holding the file, {@code #} starts a comment, a trailing {@code /} means "this folder
 * and everything under it", a leading {@code !} re-includes something an earlier rule excluded,
 * and a pattern without a slash matches at any depth.
 */
final class IgnoreRules {

    private final Path base;
    private final List<Rule> rules;

    private IgnoreRules(Path base, List<Rule> rules) {
        this.base = base;
        this.rules = rules;
    }

    static IgnoreRules read(Path ignoreFile) {
        Path base = ignoreFile.getParent();
        List<Rule> rules = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(ignoreFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                boolean negated = trimmed.startsWith("!");
                String pattern = negated ? trimmed.substring(1).trim() : trimmed;
                if (pattern.isEmpty()) {
                    continue;
                }
                rules.add(Rule.of(pattern, negated));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + ignoreFile, e);
        }
        return new IgnoreRules(base, rules);
    }

    /**
     * Whether these rules exclude {@code file}, or {@code null} when no rule applies — so callers
     * can distinguish "explicitly re-included" from "not mentioned at all".
     */
    Boolean matches(Path file) {
        if (!file.startsWith(base)) {
            return null;
        }
        String relative = base.relativize(file).toString().replace('\\', '/');
        Boolean verdict = null;
        for (Rule rule : rules) {
            if (rule.matches(relative)) {
                verdict = !rule.negated();
            }
        }
        return verdict;
    }

    private record Rule(PathMatcher fullPath, PathMatcher fileName, boolean negated, String source) {

        static Rule of(String pattern, boolean negated) {
            String normalised = pattern.endsWith("/") ? pattern + "**" : pattern;
            PathMatcher full = FileSystems.getDefault().getPathMatcher("glob:" + normalised);
            PathMatcher name = normalised.contains("/")
                    ? null
                    : FileSystems.getDefault().getPathMatcher("glob:" + normalised);
            return new Rule(full, name, negated, pattern);
        }

        boolean matches(String relative) {
            Path asPath = Path.of(relative);
            if (fullPath.matches(asPath)) {
                return true;
            }
            return fileName != null && fileName.matches(asPath.getFileName());
        }
    }
}
