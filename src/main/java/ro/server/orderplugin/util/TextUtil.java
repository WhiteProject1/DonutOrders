package ro.server.orderplugin.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;

public class TextUtil {

    private static final ConcurrentHashMap<String, String> MATERIAL_NAME_CACHE = new ConcurrentHashMap<>();

    /**
     * Patterns are compiled <b>once</b>.
     *
     * <p>{@code colorize} used to call {@code Pattern.compile} on every invocation.
     * A single menu open calls this method more than 300 times (54 slots x name
     * + lore lines); compiling is far more expensive than the matching itself
     * and would pile up on the main thread.</p>
     */
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([a-fA-F0-9]{6})");
    private static final Pattern HEX_SECTION_PATTERN = Pattern.compile("\u00a7x(\u00a7[0-9a-fA-F]){6}");

    public static String colorize(String text) {
        if (text == null) {
            return "";
        }
        // The vast majority of text has no hex color; bailing out without ever
        // running the regex engine makes the common case cheap.
        if (text.indexOf("&#") >= 0) {
            Matcher matcher = HEX_PATTERN.matcher(text);
            StringBuilder buffer = new StringBuilder(text.length() + 16);
            while (matcher.find()) {
                try {
                    matcher.appendReplacement(buffer,
                            net.md_5.bungee.api.ChatColor.of("#" + matcher.group(1)).toString());
                } catch (Exception e) {
                    matcher.appendReplacement(buffer, "");
                }
            }
            matcher.appendTail(buffer);
            text = buffer.toString();
        }
        if (text.indexOf('&') < 0) {
            return text;
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public static String stripColor(String text) {
        if (text == null) {
            return "";
        }
        if (text.indexOf('\u00a7') < 0) {
            return text;
        }
        text = HEX_SECTION_PATTERN.matcher(text).replaceAll("");
        return ChatColor.stripColor(text);
    }

    public static String formatNumber(double value) {
        if (value < 1000.0) {
            if (value == (double) ((long) value)) {
                return String.format("%d", (long) value);
            }
            return String.format("%.2f", value);
        }
        if (value < 1000000.0) {
            double k = value / 1000.0;
            if (k == (double) ((long) k)) {
                return String.format("%dK", (long) k);
            }
            return String.format("%.2fK", k);
        }
        if (value < 1.0E9) {
            double m = value / 1000000.0;
            if (m == (double) ((long) m)) {
                return String.format("%dM", (long) m);
            }
            return String.format("%.2fM", m);
        }
        if (value < 1.0E12) {
            double b = value / 1.0E9;
            if (b == (double) ((long) b)) {
                return String.format("%dB", (long) b);
            }
            return String.format("%.2fB", b);
        }
        double t = value / 1.0E12;
        if (t == (double) ((long) t)) {
            return String.format("%dT", (long) t);
        }
        return String.format("%.2fT", t);
    }

    /**
     * Full integer formatting with a thousands separator, no abbreviation:
     * {@code 45000000 -> "45.000.000"}.
     *
     * <p>{@link #formatNumber} abbreviates to K/M/B/T; this method never
     * abbreviates, it only groups. Added because the order lore template
     * ({@code gui.order-lore.template}) needs the exact figure —
     * {@code formatNumber}'s behavior was left untouched, so callers can
     * still use the old one.</p>
     *
     * <p>The decimal separator is chosen automatically so it never collides
     * with the thousands separator: if the thousands separator is {@code "."}
     * the decimal becomes {@code ","}, otherwise {@code "."}.</p>
     *
     * @param value               the number to format
     * @param thousandsSeparator  thousands group separator (e.g. {@code "."}); {@code "."} if empty/null
     * @param decimals            number of decimal places (negative is treated as 0)
     */
    public static String formatFull(double value, String thousandsSeparator, int decimals) {
        String separator = (thousandsSeparator == null || thousandsSeparator.isEmpty()) ? "." : thousandsSeparator;
        int scale = Math.max(0, decimals);

        boolean negative = value < 0;
        java.math.BigDecimal amount = java.math.BigDecimal.valueOf(Math.abs(value))
                .setScale(scale, java.math.RoundingMode.HALF_UP);
        String plain = amount.toPlainString();

        int dot = plain.indexOf('.');
        String integerPart = dot >= 0 ? plain.substring(0, dot) : plain;
        String decimalPart = dot >= 0 ? plain.substring(dot + 1) : "";

        StringBuilder grouped = new StringBuilder(integerPart.length() + 4);
        int sinceGroup = 0;
        for (int i = integerPart.length() - 1; i >= 0; i--) {
            grouped.append(integerPart.charAt(i));
            sinceGroup++;
            if (sinceGroup % 3 == 0 && i != 0) grouped.append(separator);
        }
        grouped.reverse();

        StringBuilder result = new StringBuilder();
        if (negative) result.append('-');
        result.append(grouped);
        if (scale > 0) {
            String decimalSeparator = ".".equals(separator) ? "," : ".";
            result.append(decimalSeparator).append(decimalPart);
        }
        return result.toString();
    }

    /**
     * Colored progress bar, e.g. {@code &#49F267■■■■&#555555■■■■■■}.
     *
     * <p>Color codes are left raw (&#RRGGBB) in the output; the caller must
     * apply {@link #colorize} or an equivalent mechanism (e.g. {@link LoreTemplate})
     * afterward. That way the bar can be tested on its own, and applying color
     * to it more than once does no harm (since it's idempotent).</p>
     *
     * @param filled       the amount filled
     * @param needed       target amount; if 0 or negative and {@code filled > 0} the bar is treated as full
     * @param length       total character count (returns empty if 0 or negative)
     * @param filledChar   character used for a filled cell
     * @param emptyChar    character used for an empty cell
     * @param filledColor  color code for the filled portion (e.g. {@code &#49F267})
     * @param emptyColor   color code for the empty portion (e.g. {@code &#555555})
     */
    public static String progressBar(double filled, double needed, int length, String filledChar, String emptyChar,
            String filledColor, String emptyColor) {
        if (length <= 0) return "";

        double ratio;
        if (needed <= 0) {
            ratio = filled > 0 ? 1.0 : 0.0;
        } else {
            ratio = filled / needed;
        }
        if (ratio < 0) ratio = 0;
        if (ratio > 1) ratio = 1;

        int filledCount = (int) Math.round(ratio * length);
        if (filledCount < 0) filledCount = 0;
        if (filledCount > length) filledCount = length;
        int emptyCount = length - filledCount;

        // A color code is only appended if that section has at least one
        // character: a bar with zero filled (or zero empty) cells doesn't
        // waste an unused color code - e.g. at filled=0 only the empty-color
        // code shows up.
        StringBuilder out = new StringBuilder();
        if (filledCount > 0) {
            if (filledColor != null) out.append(filledColor);
            if (filledChar != null) out.append(filledChar.repeat(filledCount));
        }
        if (emptyCount > 0) {
            if (emptyColor != null) out.append(emptyColor);
            if (emptyChar != null) out.append(emptyChar.repeat(emptyCount));
        }
        return out.toString();
    }

    public static String formatMaterialName(String materialName) {
        if (materialName == null || materialName.isEmpty()) {
            return "Unknown";
        }
        return MATERIAL_NAME_CACHE.computeIfAbsent(materialName, name -> {
            String[] words = name.toLowerCase().split("_");
            StringBuilder sb = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) continue;
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
            }
            return sb.toString().trim();
        });
    }

    public static String formatTimeRemaining(long expiryTimestamp) {
        long remaining = expiryTimestamp - System.currentTimeMillis();
        if (remaining <= 0L) {
            return "Expired";
        }
        long days = TimeUnit.MILLISECONDS.toDays(remaining);
        remaining -= TimeUnit.DAYS.toMillis(days);
        long hours = TimeUnit.MILLISECONDS.toHours(remaining);
        remaining -= TimeUnit.HOURS.toMillis(hours);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(remaining);
        StringBuilder sb = new StringBuilder();
        if (days > 0L) {
            sb.append(days).append("d ");
        }
        if (hours > 0L) {
            sb.append(hours).append("h ");
        }
        sb.append(minutes).append("m");
        return sb.toString();
    }

    public static String formatPotionName(String potionName) {
        if (potionName == null || potionName.isEmpty()) {
            return "";
        }
        switch (potionName.toUpperCase()) {
            case "INSTANT_HEAL": return "Instant Health";
            case "INSTANT_DAMAGE": return "Instant Damage";
            case "REGEN": return "Regeneration";
            case "SPEED":
            case "SWIFTNESS": return "Swiftness";
            case "LONG_SWIFTNESS": return "Long Swiftness";
            case "STRONG_SWIFTNESS": return "Strong Swiftness";
            case "JUMP": return "Leaping";
            case "LONG_LEAPING": return "Long Leaping";
            case "STRONG_LEAPING": return "Strong Leaping";
            default: return formatMaterialName(potionName);
        }
    }

    public static String formatPotionTypeName(String potionTypeStr) {
        if (potionTypeStr == null || potionTypeStr.isEmpty()) {
            return "";
        }
        String[] parts = potionTypeStr.split("_");
        if (parts.length >= 3) {
            String baseName = parts[0];
            boolean isLong = Boolean.parseBoolean(parts[1]);
            boolean isStrong = Boolean.parseBoolean(parts[2]);
            String formatted = formatPotionName(baseName);
            if (isStrong) {
                return formatted + " II";
            }
            if (isLong) {
                return formatted + " (Long)";
            }
            return formatted;
        }
        return formatPotionName(potionTypeStr);
    }

    public static String formatEnchantmentName(String enchantmentName) {
        if (enchantmentName == null || enchantmentName.isEmpty()) {
            return "Unknown";
        }
        return formatMaterialName(enchantmentName);
    }

    public static String formatName(String name) {
        if (name == null || name.isEmpty()) {
            return "Unknown";
        }
        String[] words = name.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            if (word.isEmpty()) continue;
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    public static String formatEnchantedBookName(String enchantmentStr) {
        if (enchantmentStr == null || enchantmentStr.isEmpty()) {
            return "Enchanted Book";
        }
        String[] parts = enchantmentStr.split("_");
        if (parts.length < 2) {
            return formatEnchantmentName(enchantmentStr);
        }
        try {
            int level = Integer.parseInt(parts[parts.length - 1]);
            String enchName = parts[0];
            for (int i = 1; i < parts.length - 1; ++i) {
                enchName = enchName + "_" + parts[i];
            }
            String romanLevel = level > 1 ? " " + toRoman(level) : "";
            return formatEnchantmentName(enchName) + romanLevel + " Book";
        } catch (NumberFormatException e) {
            return formatEnchantmentName(enchantmentStr) + " Book";
        }
    }

    public static String toRoman(int number) {
        if (number <= 0 || number > 10) {
            return String.valueOf(number);
        }
        String[] romanNumerals = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return romanNumerals[number];
    }
}
