package ro.server.orderplugin.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;

public class TextUtil {

    private static final ConcurrentHashMap<String, String> MATERIAL_NAME_CACHE = new ConcurrentHashMap<>();

    /**
     * Desenler <b>bir kez</b> derlenir.
     *
     * <p>Eskiden {@code colorize} her cagrida {@code Pattern.compile} yapardi.
     * Tek bir menu acilisinda bu metot 300'den fazla kez cagrilir (54 slot x isim
     * + lore satirlari); derleme islemi eslesmenin kendisinden kat kat pahalidir
     * ve ana is parcaciginda birikirdi.</p>
     */
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([a-fA-F0-9]{6})");
    private static final Pattern HEX_SECTION_PATTERN = Pattern.compile("\u00a7x(\u00a7[0-9a-fA-F]){6}");

    public static String colorize(String text) {
        if (text == null) {
            return "";
        }
        // Metinlerin buyuk cogunlugunda hex renk yoktur; regex motorunu hic
        // calistirmadan cikmak en sik gorulen durumu ucuzlatir.
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
     * Binlik ayracli, kisaltmasiz tam sayi bicimi: {@code 45000000 -> "45.000.000"}.
     *
     * <p>{@link #formatNumber} K/M/B/T'ye kisaltir; bu metot kisaltmaz, sadece
     * gruplar. Sipariş lore sablonu ({@code gui.order-lore.template}) tam
     * rakam istedigi icin eklendi — {@code formatNumber}'in davranisi
     * degistirilmedi, cagiran kod hala eskisini kullanabilir.</p>
     *
     * <p>Ondalik ayraci, binlik ayracla karismasin diye otomatik secilir:
     * binlik ayrac {@code "."} ise ondalik {@code ","} olur, degilse {@code "."}.</p>
     *
     * @param value               bicimlendirilecek sayi
     * @param thousandsSeparator  binlik grup ayraci (orn. {@code "."}); bos/null ise {@code "."}
     * @param decimals            ondalik basamak sayisi (negatifse 0 kabul edilir)
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
     * Renkli ilerleme cubugu, orn. {@code &#49F267■■■■&#555555■■■■■■}.
     *
     * <p>Renk kodlari cikti icinde ham (&#RRGGBB) kalir; cagiran taraf sonunda
     * {@link #colorize} ya da esdeger bir mekanizma (orn. {@link LoreTemplate})
     * uygulamalidir. Boylece bar tek basina test edilebilir ve birden fazla
     * kez renklendirilmesi (idempotent oldugu icin) zarar vermez.</p>
     *
     * @param filled       dolan miktar
     * @param needed       hedef miktar; 0 ya da negatifse ve {@code filled > 0} ise cubuk tam dolu sayilir
     * @param length       toplam karakter sayisi (0 veya negatifse bos donderilir)
     * @param filledChar   dolu hucre karakteri
     * @param emptyChar    bos hucre karakteri
     * @param filledColor  dolu kisimin renk kodu (orn. {@code &#49F267})
     * @param emptyColor   bos kisimin renk kodu (orn. {@code &#555555})
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

        // Renk kodu yalnizca o kisimdan en az bir karakter varsa eklenir: sifir
        // dolu (ya da sifir bos) bir cubukta kullanilmayan renk kodu bosuna
        // kalmaz - mockup'ta da (filled=0) yalnizca bos-renk kodu gorunur.
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
