package ro.server.orderplugin.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the line list coming from config ({@code lang/*.yml -> ...template})
 * into actual lore.
 *
 * <p>There are two kinds of placeholders:</p>
 * <ul>
 *   <li><b>Scalar</b> (like {@code %price%}) — replaced with its value at the
 *       spot it appears within the line.</li>
 *   <li><b>List</b> (like {@code %enchants%}) — if the line consists <b>entirely</b>
 *       of just this placeholder, that single line expands into as many lines
 *       as there are elements in the list. If the list is empty the line is
 *       <b>removed</b> entirely — it doesn't leave a blank line behind. That
 *       way an order with no enchantments doesn't leave a gap in the
 *       enchantment section.</li>
 * </ul>
 *
 * <p>An unrecognized placeholder (typo, deleted key) is left as-is; a single
 * typo by the server owner shouldn't break the whole GUI.</p>
 *
 * <p>Colorizing ({@code &#RRGGBB}, {@code &a}, {@code §a}) is applied last,
 * per line, via {@link TextUtil#colorize}; placeholder values (e.g. an item
 * name a player typed in color) get colorized this way too.</p>
 */
public final class LoreTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_-]+)%");

    private LoreTemplate() {
    }

    /**
     * @param template   raw lines from config ({@code ''} deliberately means an empty line)
     * @param values     scalar placeholder -> value (uncolored, raw text)
     * @param listValues list placeholder -> ready-made (already formatted) lines
     * @return colorized lore, ready to display
     */
    public static List<String> render(List<String> template, Map<String, String> values,
            Map<String, List<String>> listValues) {
        if (template == null || template.isEmpty()) return List.of();

        List<String> out = new ArrayList<>(template.size());
        for (String line : template) {
            if (line == null) continue;

            String listKey = pureListPlaceholder(line);
            if (listKey != null && listValues != null && listValues.containsKey(listKey)) {
                List<String> items = listValues.get(listKey);
                if (items != null) {
                    for (String item : items) {
                        if (item == null) continue;
                        out.add(TextUtil.colorize(item));
                    }
                }
                // If the list is empty the line is never added -> no gap left behind.
                continue;
            }

            out.add(TextUtil.colorize(substitute(line, values)));
        }
        return out;
    }

    /**
     * If the line consists entirely (ignoring whitespace) of a single
     * {@code %name%} placeholder, returns that name; otherwise {@code null}.
     */
    private static String pureListPlaceholder(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 3 || trimmed.charAt(0) != '%' || trimmed.charAt(trimmed.length() - 1) != '%') {
            return null;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1);
        if (inner.isEmpty() || inner.indexOf('%') >= 0) return null;
        return inner;
    }

    private static String substitute(String line, Map<String, String> values) {
        if (line.isEmpty() || values == null || values.isEmpty() || line.indexOf('%') < 0) return line;

        Matcher matcher = PLACEHOLDER.matcher(line);
        StringBuilder buffer = new StringBuilder(line.length() + 16);
        while (matcher.find()) {
            String replacement = values.get(matcher.group(1));
            // Unknown placeholder -> left as-is (does not throw).
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
