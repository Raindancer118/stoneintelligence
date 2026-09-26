package de.tstieh.stoneintelligence.stoneai.note;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Models write LaTeX as {@code \( … \)} and {@code \[ … \]}; Obsidian renders only {@code $ … $}
 * and {@code $$ … $$}. Code blocks and inline code are left exactly as they are.
 */
public final class MathDelimiters {

    /** Fenced blocks and inline code - everything else is prose that may carry formulas. */
    private static final Pattern CODE = Pattern.compile("```.*?```|`[^`\\n]*`", Pattern.DOTALL);
    private static final Pattern DISPLAY = Pattern.compile("\\\\\\[(.+?)\\\\\\]", Pattern.DOTALL);
    private static final Pattern INLINE = Pattern.compile("\\\\\\((.+?)\\\\\\)");

    private MathDelimiters() {
    }

    public static String forObsidian(String markdown) {
        StringBuilder result = new StringBuilder();
        Matcher code = CODE.matcher(markdown);
        int last = 0;
        while (code.find()) {
            result.append(convert(markdown.substring(last, code.start()))).append(code.group());
            last = code.end();
        }
        return result.append(convert(markdown.substring(last))).toString();
    }

    private static String convert(String prose) {
        String display = DISPLAY.matcher(prose).replaceAll(match -> Matcher.quoteReplacement("$$" + match.group(1).strip() + "$$"));
        return INLINE.matcher(display).replaceAll(match -> Matcher.quoteReplacement("$" + match.group(1).strip() + "$"));
    }
}
