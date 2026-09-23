package de.raindancer118.stoneai.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Takes out of a paged document what is layout, not content - essential for lecture slides:
 * <ol>
 *   <li>page numbers ({@code 36 / 244}, {@code Folie 12}) at the top or bottom of a page;</li>
 *   <li>animation steps: Beamer and PowerPoint exports show a slide once per revealed bullet - a
 *       page whose lines all reappear on the next page is an earlier step and goes;</li>
 *   <li>the header and footer every slide repeats (lecturer, course, term): a short line on at
 *       least {@value #BOILERPLATE_SHARE_PERCENT}&nbsp;% of the pages, including the first and the
 *       last quarter, once there are enough pages to tell layout from content.</li>
 * </ol>
 * The order matters: a slide title repeats on every step of its slide, so steps are collapsed
 * before counting repetitions. Pages left empty are dropped; nothing is sent to a model twice.
 */
public final class PageCleaner {

    static final int BOILERPLATE_SHARE_PERCENT = 60;
    private static final int MIN_PAGES_FOR_BOILERPLATE = 4;
    private static final int MAX_BOILERPLATE_LINE = 100;
    private static final int MAX_OUTLINE_PART = 60;

    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "(?i)^\\s*(\\d{1,4}|\\d{1,4}\\s*/\\s*\\d{1,4}|(seite|folie|slide|page)\\s+\\d{1,4}(\\s*(von|of|/)\\s*\\d{1,4})?)\\s*$");

    private PageCleaner() {
    }

    public static SourceDocument clean(SourceDocument document) {
        if (document.kind() != DocumentKind.PDF || document.pages().size() < 2) {
            return document;
        }
        List<Page> pages = new ArrayList<>();
        for (Page page : document.pages()) {
            pages.add(new Page(page.number(), withoutPageNumbers(page.text()), page.fromOcr()));
        }
        pages = withoutAnimationSteps(pages);
        pages = withoutBoilerplate(pages);
        List<Page> kept = pages.stream().filter(page -> !page.text().isBlank()).toList();
        return new SourceDocument(document.file(), document.title(), document.kind(), kept, document.skippedPages(),
                document.truncated(), document.sha256());
    }

    /**
     * One line per slide title, consecutive repeats merged: {@code "S. 12: Kostenmanagement › Target
     * Costing"}. Lets a planner see the structure of a 200-page deck in a few thousand characters.
     */
    public static List<String> outline(SourceDocument document) {
        List<String> outline = new ArrayList<>();
        String previous = null;
        for (Page page : document.pages()) {
            List<String> lines = lines(page.text());
            if (lines.isEmpty()) {
                continue;
            }
            String title = shorten(lines.get(0));
            if (lines.size() > 1 && isHeadingLike(lines.get(1)) && !lines.get(1).equals(lines.get(0))) {
                title += " › " + shorten(lines.get(1));
            }
            if (!title.equals(previous)) {
                outline.add("S. " + page.number() + ": " + title);
                previous = title;
            }
        }
        return outline;
    }

    private static String withoutPageNumbers(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        trimEdge(lines, true);
        trimEdge(lines, false);
        return String.join("\n", lines).strip();
    }

    private static void trimEdge(List<String> lines, boolean top) {
        while (!lines.isEmpty()) {
            String edge = top ? lines.getFirst() : lines.getLast();
            if (!edge.isBlank() && !PAGE_NUMBER.matcher(edge).matches()) {
                return;
            }
            if (top) {
                lines.removeFirst();
            } else {
                lines.removeLast();
            }
        }
    }

    private static List<Page> withoutAnimationSteps(List<Page> pages) {
        List<Page> kept = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            if (i + 1 < pages.size() && isEarlierStep(pages.get(i), pages.get(i + 1))) {
                continue;
            }
            kept.add(pages.get(i));
        }
        return kept;
    }

    /** Every line of {@code page} (as often as it occurs) reappears on {@code next} - an earlier step or the same slide again. */
    private static boolean isEarlierStep(Page page, Page next) {
        List<String> lines = lines(page.text());
        List<String> nextLines = lines(next.text());
        if (lines.isEmpty() || lines.size() > nextLines.size()) {
            return false;
        }
        Map<String, Integer> available = counts(nextLines);
        for (String line : lines) {
            int left = available.getOrDefault(line, 0);
            if (left == 0) {
                return false;
            }
            available.put(line, left - 1);
        }
        return true;
    }

    private static List<Page> withoutBoilerplate(List<Page> pages) {
        if (pages.size() < MIN_PAGES_FOR_BOILERPLATE) {
            return pages;
        }
        Map<String, Integer> pagesWithLine = new HashMap<>();
        Set<String> inFirstQuarter = new LinkedHashSet<>();
        Set<String> inLastQuarter = new LinkedHashSet<>();
        int quarter = Math.max(1, pages.size() / 4);
        for (int i = 0; i < pages.size(); i++) {
            for (String line : new LinkedHashSet<>(lines(pages.get(i).text()).stream().map(PageCleaner::shape).toList())) {
                if (line.length() <= MAX_BOILERPLATE_LINE) {
                    pagesWithLine.merge(line, 1, Integer::sum);
                    if (i < quarter) {
                        inFirstQuarter.add(line);
                    }
                    if (i >= pages.size() - quarter) {
                        inLastQuarter.add(line);
                    }
                }
            }
        }
        // A footer runs through the whole deck; a chapter heading fills one stretch of it, however
        // long - that is structure and stays.
        Set<String> boilerplate = new LinkedHashSet<>();
        pagesWithLine.forEach((line, count) -> {
            if (count * 100 >= pages.size() * BOILERPLATE_SHARE_PERCENT
                    && inFirstQuarter.contains(line) && inLastQuarter.contains(line)) {
                boilerplate.add(line);
            }
        });
        if (boilerplate.isEmpty()) {
            return pages;
        }
        List<Page> cleaned = new ArrayList<>();
        for (Page page : pages) {
            List<String> kept = new ArrayList<>();
            for (String line : page.text().split("\n", -1)) {
                if (!boilerplate.contains(shape(line.strip()))) {
                    kept.add(line);
                }
            }
            cleaned.add(new Page(page.number(), String.join("\n", kept).strip().replaceAll("\n{3,}", "\n\n"), page.fromOcr()));
        }
        return cleaned;
    }

    /**
     * A line with its numbers blanked out. Text extraction glues the page number onto the footer
     * ({@code 95Dipl. Betriebswirt …}, {@code … 36 / 244}) - one footer, a different line on every page.
     */
    private static String shape(String line) {
        return line.replaceAll("\\d+", "#");
    }

    private static List<String> lines(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static Map<String, Integer> counts(List<String> lines) {
        Map<String, Integer> counts = new HashMap<>();
        lines.forEach(line -> counts.merge(line, 1, Integer::sum));
        return counts;
    }

    private static boolean isHeadingLike(String line) {
        return line.length() <= MAX_OUTLINE_PART && !line.matches(".*[.:;,]$") && !line.startsWith("•") && !line.startsWith("-");
    }

    private static String shorten(String line) {
        return line.length() <= MAX_OUTLINE_PART ? line : line.substring(0, MAX_OUTLINE_PART).strip() + "…";
    }
}
