package ro.server.orderplugin.i18n;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.config.Settings;
import ro.server.orderplugin.util.MiniFont;
import ro.server.orderplugin.util.TextUtil;

/**
 * Alici bazli (per-player) cok dilli metin cozumleyici.
 *
 * <p>Her metin {@code lang/<kod>.yml} icinde yasar. {@code lang/en.yml} kanonik kaynaktir:
 * bir cevirinin eksik biraktigi her anahtar oradan tamamlanir.</p>
 *
 * <p>Bir alici icin cozum sirasi:</p>
 * <ol>
 *   <li>oyuncunun kendi secimi (/orderlang, diskte saklanir)</li>
 *   <li>oyuncunun Minecraft istemci dili (otomatik)</li>
 *   <li>sunucu varsayilani (config.yml -> language)</li>
 *   <li>Ingilizce</li>
 * </ol>
 *
 * <p>Eski kurulumlardan gelen {@code messages.yml} ilk aciliste sunucunun varsayilan
 * dil dosyasina tasinir; boylece surum yukseltmesinde sunucu sahibinin duzenledigi
 * metinler kaybolmaz ve hicbir dosyayi elle silmek zorunda kalmaz.</p>
 */
public final class LanguageManager {

    /** Eklentiyle birlikte gelen diller. */
    /**
     * Jar ile gelen diller.
     *
     * <p>Sunucu sahibi {@code lang/} klasorune kendi dosyasini birakarak 14., 15.
     * dili ekleyebilir; {@link #load()} klasordeki her {@code *.yml} dosyasini da
     * tarar, bu liste yalnizca "jar'dan cikarilacaklar" demektir.</p>
     *
     * <p>Sira onemli degil ama {@code en} her zaman burada olmali: eksik anahtarlar
     * ona duser.</p>
     */
    public static final List<String> BUNDLED = List.of(
            "en", "tr", "de", "es", "fr", "it", "pt", "ru", "pl", "nl", "cs", "zh", "ja");

    private final OrderPlugin plugin;

    private final Map<String, YamlConfiguration> overlays = new HashMap<>();   // kod -> dil dosyasi
    private final Map<String, String> localeMap = new HashMap<>();             // normalize locale -> kod
    private final Map<UUID, String> overrides = new ConcurrentHashMap<>();     // oyuncunun kendi secimi
    private final Map<String, String> cache = new ConcurrentHashMap<>();       // kod\0anahtar -> ham metin

    /**
     * Onek eklenmis + minifont uygulanmis + renklendirilmis metin.
     *
     * <p>Bu uc islem yalnizca dil dosyasina ve config'e bagli; ayni anahtar icin
     * her cagrida ayni sonucu verir. Eskiden her {@link #msg} cagrisinda bastan
     * yapiliyordu: tek bir menu acilisinda 300'den fazla kez onek arama
     * ({@code getConfig().getString}), minifont karakter donusumu ve hex renk
     * regex'i calisiyordu. Artik yalnizca yer tutucu degistirme kaliyor.</p>
     *
     * <p>{@link #load()} bu onbellegi de temizler, yani {@code /reload} sonrasi
     * eski metin takili kalmaz.</p>
     */
    private final Map<String, String> prepared = new ConcurrentHashMap<>();    // kod\0anahtar -> hazir metin

    /** Onek her mesajda config'den okunmasin diye yuklemede bir kez alinir. */
    private String prefix = "";

    private String serverDefault = "tr";
    private boolean perPlayer = true;

    public LanguageManager(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ yukleme

    /** Paketli dosyalari (eksikse) cikarir ve tum dilleri bellege alir. */
    public void load() {
        overlays.clear();
        localeMap.clear();
        cache.clear();
        prepared.clear();

        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("lang/ klasoru olusturulamadi: " + dir);
        }

        migrateLegacyMessages(dir);

        int addedTotal = 0;
        for (String code : BUNDLED) {
            extractIfMissing(dir, code);
            File file = new File(dir, code + ".yml");
            YamlConfiguration bundled = loadBundled(code);

            if (!file.exists()) {
                if (bundled != null) overlays.put(code, bundled);
                buildLocaleKeys(code);
                continue;
            }

            YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
            // Surum yukseltmesinde yeni anahtarlari getir. Birlestirme ONCE bellekte yapilir:
            // dosyaya yazamasak bile oyuncu "eksik metin" gormemeli.
            int added = mergeMissing(onDisk, bundled);
            if (added > 0) {
                addedTotal += added;
                try {
                    onDisk.save(file);
                } catch (Exception e) {
                    plugin.getLogger().warning("lang/" + code + ".yml guncellenemedi ("
                            + e.getMessage() + "); yeni metinler yalnizca bu oturum icin gecerli.");
                }
            }
            overlays.put(code, onDisk);
            buildLocaleKeys(code);
        }
        if (addedTotal > 0) {
            plugin.getLogger().info(addedTotal + " yeni metin anahtari dil dosyalarina eklendi (surum yukseltmesi).");
        }

        // Diskte olup paketlenmemis ek diller (sunucu sahibinin kendi cevirisi) de yuklenir.
        File[] extra = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (extra != null) {
            for (File f : extra) {
                String code = f.getName().substring(0, f.getName().length() - 4);
                if (overlays.containsKey(code)) continue;
                overlays.put(code, YamlConfiguration.loadConfiguration(f));
                buildLocaleKeys(code);
            }
        }

        serverDefault = normalizeDefault(plugin.getConfig().getString("language", "tr"));
        perPlayer = plugin.getConfig().getBoolean("per-player-language", true);
        String rawPrefix = plugin.getConfig().getString("prefix", "");
        prefix = rawPrefix == null ? "" : rawPrefix;

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("locale-map");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                localeMap.put(key.toLowerCase(Locale.ROOT).replace('-', '_'), section.getString(key));
            }
        }

        plugin.getLogger().info("Diller yuklendi: " + overlays.keySet()
                + " (varsayilan=" + serverDefault + ", oyuncu-bazli=" + perPlayer + ").");
    }

    /**
     * Eski surumden kalan {@code messages.yml}'i <b>bir kez</b> dil dosyasina tasir.
     *
     * <p>Dosyayi kalici bir ezme katmani olarak okumak, sunucu sahibinin duzenledigi
     * metinleri korurdu ama oyuncu-bazli dili de olduururdu: Ingilizce oynayan biri de
     * messages.yml'deki Turkce metni gorurdu. Bu yuzden icerik sunucunun varsayilan
     * diline yazilir, dosya {@code messages.yml.migrated} olarak yeniden adlandirilir.
     * Boylece hicbir duzenleme kaybolmaz, kimse elle dosya silmek zorunda kalmaz ve
     * diller birbirine karismaz.</p>
     */
    private void migrateLegacyMessages(File langDir) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) return;

        String target = plugin.getConfig().getString("language", "tr");
        if (target == null || target.isBlank() || target.equalsIgnoreCase("auto")) target = "tr";
        target = target.trim().toLowerCase(Locale.ROOT);

        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(file);
        File targetFile = new File(langDir, target + ".yml");
        extractIfMissing(langDir, target);
        YamlConfiguration onDisk = targetFile.exists()
                ? YamlConfiguration.loadConfiguration(targetFile)
                : new YamlConfiguration();

        int moved = 0;
        for (String path : legacy.getKeys(true)) {
            if (legacy.isConfigurationSection(path)) continue;
            Object value = legacy.get(path);
            if (value == null) continue;
            if (value.equals(onDisk.get(path))) continue;
            onDisk.set(path, value);
            moved++;
        }

        try {
            onDisk.save(targetFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Eski messages.yml lang/" + target + ".yml'e tasinamadi ("
                    + e.getMessage() + "); dosya oldugu gibi birakildi.");
            return;
        }

        File archived = new File(plugin.getDataFolder(), "messages.yml.migrated");
        if (archived.exists() && !archived.delete()) {
            plugin.getLogger().warning("Eski yedek silinemedi: " + archived.getName());
        }
        if (!file.renameTo(archived)) {
            plugin.getLogger().warning("messages.yml yeniden adlandirilamadi; metinler tasindi ama dosya duruyor. "
                    + "Elle silebilirsiniz.");
            return;
        }
        plugin.getLogger().info("Eski messages.yml icindeki " + moved + " metin lang/" + target
                + ".yml'e tasindi. Yedek: messages.yml.migrated");
    }

    // ------------------------------------------------------------------ cozumleme

    /** Alicinin kullanacagi dil kodu. */
    public String resolve(CommandSender sender) {
        if (perPlayer && sender instanceof Player p) {
            String override = overrides.get(p.getUniqueId());
            if (override != null && overlays.containsKey(override)) return override;
            String byLocale = fromLocale(p);
            if (byLocale != null) return byLocale;
        }
        return serverDefault;
    }

    /** Renk kodlari uygulanmis metin; {@code %anahtar%} ciftleri sirayla degistirilir. */
    public String msg(CommandSender target, String key, String... replacements) {
        String text = prepared(resolve(target), key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        // Renklendirme yer tutucudan SONRA yapilir: yerine konan metin de
        // (ornegin sunucu sahibinin renkli yazdigi bir esya adi) renklensin.
        return TextUtil.colorize(text);
    }

    /**
     * Liste degerli metin (lore, tabela satirlari, animasyon kareleri).
     *
     * <p>Deger dosyada <b>duz metin</b> olarak yazilmissa tek satirlik liste
     * gibi islenir. Sunucu sahibi {@code price: "Fiyat yaz"} yazdiginda eskiden
     * {@code isList} false donuyor, metin yokmus gibi davranilip tabela BOS
     * aciliyordu; artik yazdigi metin gorunur.</p>
     */
    public List<String> msgList(CommandSender target, String key, String... replacements) {
        String code = resolve(target);
        List<String> list = rawList(code, key);
        if (list == null) list = rawList("en", key);
        if (list == null) list = rawList("tr", key);
        if (list == null) return List.of();
        java.util.ArrayList<String> out = new java.util.ArrayList<>(list.size());
        for (String line : list) {
            String s = line.replace("{prefix}", prefix);
            s = miniFont(key, s);
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                s = s.replace(replacements[i], replacements[i + 1]);
            }
            out.add(TextUtil.colorize(s));
        }
        return out;
    }

    /**
     * Onek eklenmis ve minifont uygulanmis metin; yer tutucular ile renk kodlari
     * <b>henuz islenmemistir</b>. Sonuc onbelleklenir.
     *
     * <p>Renklendirme disarida birakilir cunku yer tutucunun yerine gelen deger de
     * renk kodu tasiyabilir; onbellek yalnizca anahtara bagli olan (ve bu yuzden
     * degismeyen) kismi tutar.</p>
     */
    private String prepared(String code, String key) {
        return prepared.computeIfAbsent(code + '\0' + key, k -> {
            String raw = raw(code, key);
            raw = raw.replace("{prefix}", prefix);
            return miniFont(key, raw);
        });
    }

    /**
     * Minifont donusumunu metnin <b>turune</b> gore uygular.
     *
     * <p>Tur, dil anahtarinin onekinden anlasilir; boylece her cagri yerine ayri
     * bir parametre eklemek gerekmez ve yeni bir buton eklendiginde otomatik
     * dogru davranir:</p>
     * <ul>
     *   <li>{@code *.buttons.*} -> {@code text.minifont.buttons}</li>
     *   <li>{@code menus.*}     -> {@code text.minifont.titles}</li>
     *   <li>{@code *.lore.*}    -> {@code text.minifont.lore}</li>
     *   <li>digerleri (sohbet mesajlari) -> {@code text.minifont.messages}</li>
     * </ul>
     *
     * <p>Lore sohbet mesajlarindan ayri tutuldu: buton aciklamalari menude
     * butonun kendisiyle yan yana durur ve biri kucuk harfliyken digerinin normal
     * olmasi dagilmis gorunur. Sohbet mesajlari ise uzun cumlelerdir, kucuk harfe
     * cevrilince okunmasi zorlasir — bu yuzden onlar varsayilan olarak kapali.</p>
     *
     * <p>Donusum yer tutucu <b>degistirilmeden once</b> yapilir: etiket kucuk harfe
     * doner ama icine yazilan oyuncu adi, fiyat ve esya adi okunakli kalir.</p>
     */
    private String miniFont(String key, String text) {
        if (key == null || text == null || text.isEmpty()) return text;
        Settings settings = plugin.settings();
        if (settings == null) return text;

        boolean apply;
        if (key.startsWith("menus.")) {
            apply = settings.miniFontTitles();
        } else if (key.contains(".buttons.")) {
            apply = settings.miniFontButtons();
        } else if (key.contains(".lore.")) {
            apply = settings.miniFontLore();
        } else {
            apply = settings.miniFontMessages();
        }
        return apply ? MiniFont.apply(text, settings.miniFontMap()) : text;
    }

    /** Anahtarin bu dilde tanimli olup olmadigi (fallback dahil). */
    public boolean has(String key) {
        for (YamlConfiguration ov : overlays.values()) {
            if (ov.isSet(key)) return true;
        }
        return false;
    }

    /** Ham metin — renk kodu uygulanmamis, yer tutucular degistirilmemis. */
    public String raw(String code, String key) {
        return cache.computeIfAbsent(code + '\0' + key, k -> {
            String value = lookup(code, key);
            if (value == null) {
                plugin.getLogger().warning("Eksik metin anahtari: " + key);
                value = "&c[" + key + "]";
            }
            return value;
        });
    }

    /**
     * {@link #raw} ile ayni cozumleme, ama anahtar yoksa <b>uyari basmaz</b> ve
     * {@code null} doner.
     *
     * <p>Bazi anahtarlarin yoklugu normaldir: {@code names.items.OAK_LOG} gibi
     * "istege bagli tam ad" girdileri dil dosyalarinda bilerek bostur ve kelime
     * sozlugunden uretilir. {@link #raw} bunlarin her biri icin bir uyari
     * basiyordu — esya secme menusu ilk acildiginda konsola ~1300 satir dolduran,
     * her biri diske senkron yazilan bir gurultu olusuyordu.</p>
     */
    public String rawOrNull(String code, String key) {
        return lookup(code, key);
    }

    /** Oyuncunun dili -> Ingilizce -> Turkce sirasiyla arar; hicbirinde yoksa null. */
    private String lookup(String code, String key) {
        YamlConfiguration ov = overlays.get(code);
        String value = ov == null ? null : ov.getString(key);
        if (value == null) {
            YamlConfiguration en = overlays.get("en");
            if (en != null) value = en.getString(key);
        }
        if (value == null) {
            YamlConfiguration tr = overlays.get("tr");
            if (tr != null) value = tr.getString(key);
        }
        return value;
    }

    /**
     * Liste degerli ham metin — renk kodu uygulanmamis, yer tutucular degistirilmemis,
     * ve <b>yalnizca verilen dilde</b> aranir (ing/tr fallback yapmaz).
     *
     * <p>Deger duz metin olarak yazilmissa tek elemanli liste dondurulur — sunucu
     * sahibinin bicimi degistirmesi metni kaybettirmez. Anahtar hic yoksa
     * {@code null} doner; cagiran taraf (orn. lore sablonlari) bunu "bu dilde
     * tanimli degil, digerine bak ya da eski koda dus" olarak yorumlayabilir.</p>
     */
    public List<String> rawList(String code, String key) {
        YamlConfiguration ov = overlays.get(code);
        if (ov == null) return null;
        if (ov.isList(key)) return ov.getStringList(key);
        String single = ov.getString(key);
        return single == null ? null : List.of(single);
    }

    /** Bir bolumun (section) altindaki anahtarlar — sozluk yuklemek icin. */
    public ConfigurationSection section(String code, String path) {
        YamlConfiguration ov = overlays.get(code);
        return ov == null ? null : ov.getConfigurationSection(path);
    }

    // ------------------------------------------------------------------ oyuncu secimi

    public boolean isSupported(String code) {
        return overlays.containsKey(code);
    }

    public Set<String> available() {
        return overlays.keySet();
    }

    public void setOverride(UUID uuid, String code) {
        if (code == null) overrides.remove(uuid);
        else overrides.put(uuid, code);
    }

    public String getOverride(UUID uuid) {
        return overrides.get(uuid);
    }

    public void unload(UUID uuid) {
        overrides.remove(uuid);
    }

    public boolean isPerPlayer() {
        return perPlayer;
    }

    public String serverDefault() {
        return serverDefault;
    }

    /** Dilin kendi adi (lang dosyasindaki {@code language.name}), menude gostermek icin. */
    public String displayName(String code) {
        YamlConfiguration ov = overlays.get(code);
        String name = ov == null ? null : ov.getString("language.name");
        return name != null ? name : code;
    }

    // ------------------------------------------------------------------ yardimcilar

    private String fromLocale(Player p) {
        try {
            String loc = p.locale().toString().toLowerCase(Locale.ROOT).replace('-', '_'); // tr_tr, pt_br
            String hit = localeMap.get(loc);
            if (hit != null) return hit;
            int us = loc.indexOf('_');
            if (us > 0) hit = localeMap.get(loc.substring(0, us));
            return hit;
        } catch (Throwable t) {
            return null;
        }
    }

    private void buildLocaleKeys(String code) {
        String norm = code.toLowerCase(Locale.ROOT);
        localeMap.putIfAbsent(norm, code);
        int us = norm.indexOf('_');
        if (us > 0) localeMap.putIfAbsent(norm.substring(0, us), code);
    }

    private String normalizeDefault(String raw) {
        if (raw == null) return "tr";
        String r = raw.trim();
        if (r.equalsIgnoreCase("auto")) {
            Locale machine = Locale.getDefault();
            String lang = machine.getLanguage().toLowerCase(Locale.ROOT);
            for (String code : overlays.keySet()) {
                if (code.equalsIgnoreCase(lang)) return code;
            }
            return overlays.containsKey("tr") ? "tr" : "en";
        }
        return overlays.containsKey(r) ? r : (overlays.containsKey("tr") ? "tr" : "en");
    }

    private YamlConfiguration loadBundled(String code) {
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Paketli lang/" + code + ".yml okunamadi: " + e.getMessage());
            return null;
        }
    }

    /**
     * Jar'da olup diskte olmayan anahtarlari ekler; var olanlara DOKUNMAZ.
     *
     * @return eklenen anahtar sayisi
     */
    private static int mergeMissing(YamlConfiguration onDisk, YamlConfiguration bundled) {
        if (bundled == null) return 0;
        int added = 0;
        for (String path : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(path)) continue;
            if (onDisk.contains(path)) continue;
            onDisk.set(path, bundled.get(path));
            added++;
        }
        return added;
    }

    private void extractIfMissing(File dir, String code) {
        File out = new File(dir, code + ".yml");
        if (out.exists()) return;
        try (InputStream in = plugin.getResource("lang/" + code + ".yml")) {
            if (in != null) java.nio.file.Files.copy(in, out.toPath());
        } catch (Exception e) {
            plugin.getLogger().warning("lang/" + code + ".yml cikarilamadi: " + e.getMessage());
        }
    }
}
