package com.creed.report.markdown;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A review's verdict, e.g. {@code 🟡 Approve with comments}.
 *
 * @param raw       the cell as written (plain text)
 * @param label     the words alone — {@code Approve with comments}
 * @param indicator what precedes the words — the traffic-light emoji ({@code 🟡}), a shortcode left
 *                  as written ({@code :yellow_circle:}), or null when the cell starts with a word
 */
public record Verdict(String raw, String label, String indicator) {

    private static final Pattern SHORTCODE = Pattern.compile("^(:[a-z0-9_+-]+:)\\s*");

    static Verdict of(String raw) {
        String text = raw.strip();
        int i = 0;
        // Everything before the first letter or digit: emoji are surrogate pairs, often followed by a
        // variation selector (U+FE0F) or joined by ZWJ, so walk code points rather than chars.
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) break;
            i += Character.charCount(cp);
        }
        // `:yellow_circle: Approve` — a shortcode is letters, so the loop above stops inside it.
        Matcher m = SHORTCODE.matcher(text);
        if (m.find()) return new Verdict(text, text.substring(m.end()).strip(), m.group(1));
        String indicator = text.substring(0, i).strip();
        return new Verdict(text, text.substring(i).strip(), indicator.isEmpty() ? null : indicator);
    }
}
