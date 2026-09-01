package ro.server.orderplugin.i18n;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionType;

/**
 * Translates item, enchantment, and potion names into the recipient's language.
 *
 * <p>Minecraft has ~1300 materials; writing out every single one would make
 * the language file unusable. So this works in two layers:</p>
 * <ol>
 *   <li><b>Exact name</b> ({@code names.items.OAK_LOG}) — used directly if present.
 *       This exists to fix up grammatical suffixes in languages like Turkish
 *       (e.g. "Mese Kutugu" needs a possessive-style ending that a naive
 *       word-by-word join wouldn't produce).</li>
 *   <li><b>Word dictionary</b> ({@code names.words.OAK}) — if there's no exact
 *       name, the material name is split on underscores, each piece is
 *       translated from the dictionary, and the pieces are joined back up.
 *       About 400 words cover almost all of the 1300 materials.</li>
 * </ol>
 * <p>If both layers come up empty, the English name is shown in proper case
 * ("Oak Log") — a missing translation never leaks out as the raw enum name
 * ("OAK_LOG").</p>
 */
public final class NameTranslator {

    private final LanguageManager language;

    /** code\0type\0key -> translated name. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    /** code -> word dictionary (uppercase key -> translation). */
    private final Map<String, Map<String, String>> words = new ConcurrentHashMap<>();

    public NameTranslator(LanguageManager language) {
        this.language = language;
    }

    /** Called when language files are reloaded. */
    public void invalidate() {
        cache.clear();
        words.clear();
    }

    // ------------------------------------------------------------------ public API

    public String material(CommandSender target, Material material) {
        if (material == null) return unknown(language.resolve(target));
        return material(language.resolve(target), material.name());
    }

    public String material(String code, String materialName) {
        if (materialName == null || materialName.isEmpty()) return unknown(code);
        return cache.computeIfAbsent(code + "\0m\0" + materialName, k -> {
            // rawOrNull: this key being empty is NORMAL (see the two-layer
            // explanation above), so raw(), which prints a warning, isn't used.
            String exact = language.rawOrNull(code, "names.items." + materialName);
            if (exact != null) return exact;
            return composeFromWords(code, materialName);
        });
    }

    public String enchantment(String code, String enchantKey) {
        if (enchantKey == null || enchantKey.isEmpty()) return unknown(code);
        String key = enchantKey.toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(code + "\0e\0" + key, k -> {
            String exact = language.rawOrNull(code, "names.enchantments." + key);
            if (exact != null) return exact;
            return composeFromWords(code, key.toUpperCase(Locale.ROOT));
        });
    }

    public String enchantment(String code, Enchantment enchantment) {
        return enchantment == null ? unknown(code) : enchantment(code, enchantment.getKey().getKey());
    }

    /** A leveled enchantment name like "Sharpness V". */
    public String enchantmentWithLevel(String code, String enchantKey, int level) {
        return enchantment(code, enchantKey) + " " + toRoman(level);
    }

    /** Enchanted book name: "Sharpness V Book". */
    public String enchantedBook(String code, String enchantKey, int level) {
        String pattern = language.raw(code, "names.enchanted-book");
        String name = enchantment(code, enchantKey);
        if (level > 1) name = name + " " + toRoman(level);
        return pattern.replace("%enchantment%", name);
    }

    /**
     * Potion name: base type + bottle type (splash/lingering) + duration/strength suffix.
     *
     * <p>The patterns come from the language file, so word order can be
     * translated too — "Splash Potion of Swiftness" and the Turkish
     * "Patlamali Hizlanma Iksiri" (modifier before the noun) come out of the
     * same code.</p>
     */
    public String potion(String code, Material bottle, PotionType type) {
        if (type == null) return material(code, bottle == null ? "POTION" : bottle.name());
        String cacheKey = code + "\0p\0" + (bottle == null ? "POTION" : bottle.name()) + "\0" + type.name();
        return cache.computeIfAbsent(cacheKey, k -> {
            String typeName = type.name();
            String suffixKey = null;
            if (typeName.startsWith("LONG_")) {
                typeName = typeName.substring(5);
                suffixKey = "names.potion.long";
            } else if (typeName.startsWith("STRONG_")) {
                typeName = typeName.substring(7);
                suffixKey = "names.potion.strong";
            }

            String base = language.rawOrNull(code, "names.potions." + typeName);
            if (base == null) {
                base = composeFromWords(code, typeName);
            }

            String result = language.raw(code, "names.potion.base").replace("%name%", base);
            if (suffixKey != null) {
                result = language.raw(code, suffixKey).replace("%name%", result);
            }
            if (bottle == Material.SPLASH_POTION) {
                result = language.raw(code, "names.potion.splash").replace("%name%", result);
            } else if (bottle == Material.LINGERING_POTION) {
                result = language.raw(code, "names.potion.lingering").replace("%name%", result);
            }
            return result;
        });
    }

    public String unknown(String code) {
        return language.raw(code, "names.unknown");
    }

    // ------------------------------------------------------------------ internals

    /**
     * Splits the material name on underscores and translates each piece from
     * the dictionary. A piece not in the dictionary is left as-is, capitalized.
     */
    private String composeFromWords(String code, String rawName) {
        Map<String, String> dict = wordsFor(code);
        String[] parts = rawName.split("_");
        StringBuilder sb = new StringBuilder(rawName.length() + 8);
        for (String part : parts) {
            if (part.isEmpty()) continue;
            String upper = part.toUpperCase(Locale.ROOT);
            String translated = dict.get(upper);
            if (translated == null) translated = titleCase(part);
            if (translated.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(translated);
        }
        return sb.length() == 0 ? titleCase(rawName) : sb.toString();
    }

    private Map<String, String> wordsFor(String code) {
        return words.computeIfAbsent(code, c -> {
            Map<String, String> map = new java.util.HashMap<>();
            // The English dictionary is always the base: if a translation file
            // skips a word, the English equivalent is used, not the raw enum name.
            fill(map, language.section("en", "names.words"));
            if (!"en".equals(c)) fill(map, language.section(c, "names.words"));
            return map;
        });
    }

    private static void fill(Map<String, String> map, ConfigurationSection section) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value != null) map.put(key.toUpperCase(Locale.ROOT), value);
        }
    }

    private static String titleCase(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return lower;
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static String toRoman(int number) {
        if (number <= 0 || number > 10) return String.valueOf(number);
        String[] numerals = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return numerals[number];
    }
}
