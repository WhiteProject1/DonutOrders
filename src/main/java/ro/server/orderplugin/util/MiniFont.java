package ro.server.orderplugin.util;

import java.util.Map;

/**
 * Converts text to "small caps" appearance: {@code Order -> ᴏʀᴅᴇʀ}.
 *
 * <p>The letter table comes from config ({@code text.minifont.map}); any
 * character not in the table is left as-is. So digits, punctuation, and
 * letters the server owner didn't map stay unchanged.</p>
 *
 * <p>Two things are <b>never</b> converted:</p>
 * <ul>
 *   <li><b>Color codes</b> ({@code &a}, {@code §a}, {@code &#RRGGBB}) — converting
 *       these would break the color and leave a raw code visible in the text.</li>
 *   <li><b>Placeholder names</b> ({@code %player%}) — converting these would
 *       make {@code text.replace("%player%", ...)} stop matching, and the
 *       player would see the raw placeholder on screen.</li>
 * </ul>
 *
 * <p>This class also leaves placeholder <i>values</i> untouched, because the
 * conversion is applied <b>before</b> substitution: the fixed label becomes
 * small caps, while the player name / price written into it stays readable.</p>
 */
public final class MiniFont {

    /** Color code marker (U+00A7). Can be written literally since pom.xml sourceEncoding=UTF-8. */
    private static final char SECTION = '§';

    private MiniFont() {
    }

    /**
     * @param input raw text (may contain color codes and placeholders)
     * @param map   lowercase letter -> character to display; if empty the text is unchanged
     */
    public static String apply(String input, Map<String, String> map) {
        if (input == null || input.isEmpty() || map == null || map.isEmpty()) return input;

        StringBuilder out = new StringBuilder(input.length() + 8);
        int length = input.length();
        int i = 0;
        while (i < length) {
            char c = input.charAt(i);

            // Color code: marker + one character passes through unchanged.
            if ((c == '&' || c == SECTION) && i + 1 < length) {
                // &#RRGGBB style hex colors are preserved too.
                if (input.charAt(i + 1) == '#' && i + 8 <= length) {
                    out.append(input, i, Math.min(i + 8, length));
                    i = Math.min(i + 8, length);
                    continue;
                }
                out.append(c).append(input.charAt(i + 1));
                i += 2;
                continue;
            }

            // Placeholder: everything between %name% passes through unchanged. If
            // there's no closing percent sign (a lone % in the text), it's treated
            // as a normal character.
            if (c == '%') {
                int end = input.indexOf('%', i + 1);
                if (end > i && isPlaceholderName(input, i + 1, end)) {
                    out.append(input, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }

            String mapped = map.get(String.valueOf(Character.toLowerCase(c)));
            out.append(mapped != null ? mapped : String.valueOf(c));
            i++;
        }
        return out.toString();
    }

    /**
     * Is the text between {@code %...%} actually a placeholder name?
     *
     * <p>Placeholder names contain letters/digits/underscore/hyphen. Spaces
     * aren't allowed, so a sentence like "%50 indirim%" ("%50 off%") doesn't
     * get accidentally preserved.</p>
     */
    private static boolean isPlaceholderName(String text, int from, int to) {
        if (to - from == 0 || to - from > 40) return false;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
}
