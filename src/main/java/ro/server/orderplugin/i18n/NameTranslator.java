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
 * Esya, buyu ve iksir adlarini alicinin diline cevirir.
 *
 * <p>Minecraft'ta ~1300 materyal var; hepsini tek tek yazmak dil dosyasini
 * kullanilmaz hale getirirdi. Bu yuzden iki katmanli calisir:</p>
 * <ol>
 *   <li><b>Tam ad</b> ({@code names.items.OAK_LOG}) — varsa dogrudan kullanilir.
 *       Turkce'deki tamlama eklerini ("Mese Kutugu") duzeltmek icin buradadir.</li>
 *   <li><b>Kelime sozlugu</b> ({@code names.words.OAK}) — tam ad yoksa materyal adi
 *       alt cizgiden bolunur, her parca sozlukten cevrilir ve birlestirilir.
 *       Yaklasik 400 kelime, 1300 materyalin neredeyse tamamini karsilar.</li>
 * </ol>
 * <p>Iki katman da bosa duserse Ingilizce ad duzgun bicimde ("Oak Log") gosterilir —
 * ceviri eksigi hicbir zaman ham enum adi ("OAK_LOG") olarak sizmaz.</p>
 */
public final class NameTranslator {

    private final LanguageManager language;

    /** kod\0tur\0anahtar -> cevrilmis ad. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    /** kod -> kelime sozlugu (buyuk harfli anahtar -> karsilik). */
    private final Map<String, Map<String, String>> words = new ConcurrentHashMap<>();

    public NameTranslator(LanguageManager language) {
        this.language = language;
    }

    /** Dil dosyalari yeniden yuklendiginde cagrilir. */
    public void invalidate() {
        cache.clear();
        words.clear();
    }

    // ------------------------------------------------------------------ genel API

    public String material(CommandSender target, Material material) {
        if (material == null) return unknown(language.resolve(target));
        return material(language.resolve(target), material.name());
    }

    public String material(String code, String materialName) {
        if (materialName == null || materialName.isEmpty()) return unknown(code);
        return cache.computeIfAbsent(code + "\0m\0" + materialName, k -> {
            // rawOrNull: bu anahtarin bos olmasi NORMALDIR (asagidaki iki katmanli
            // aciklamaya bakin), bu yuzden uyari basan raw() kullanilmaz.
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

    /** "Keskinlik V" gibi seviyeli buyu adi. */
    public String enchantmentWithLevel(String code, String enchantKey, int level) {
        return enchantment(code, enchantKey) + " " + toRoman(level);
    }

    /** Buyulu kitap adi: "Keskinlik V Kitabi". */
    public String enchantedBook(String code, String enchantKey, int level) {
        String pattern = language.raw(code, "names.enchanted-book");
        String name = enchantment(code, enchantKey);
        if (level > 1) name = name + " " + toRoman(level);
        return pattern.replace("%enchantment%", name);
    }

    /**
     * Iksir adi: temel tur + sise turu (patlamali/kalici) + sure/guc eki.
     *
     * <p>Kaliplar dil dosyasindan geldigi icin kelime sirasi da cevrilebilir —
     * "Splash Potion of Swiftness" ile "Patlamali Hizlanma Iksiri" ayni koddan cikar.</p>
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

    // ------------------------------------------------------------------ ic isleyis

    /**
     * Materyal adini alt cizgiden bolup her parcayi sozlukten cevirir.
     * Sozlukte olmayan parca, ilk harfi buyuk olacak sekilde oldugu gibi kalir.
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
            // Ingilizce sozluk her zaman taban: ceviri dosyasi bir kelimeyi
            // atlarsa Ingilizce karsiligi kullanilir, ham enum adi degil.
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
