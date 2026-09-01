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
 * Per-player multilingual text resolver.
 *
 * <p>Every string lives in {@code lang/<code>.yml}. {@code lang/en.yml} is the canonical
 * source: any key a translation leaves out is filled in from there.</p>
 *
 * <p>Resolution order for a given recipient:</p>
 * <ol>
 *   <li>the player's own choice (/orderlang, stored on disk)</li>
 *   <li>the player's Minecraft client language (automatic)</li>
 *   <li>server default (config.yml -> language)</li>
 *   <li>English</li>
 * </ol>
 *
 * <p>A legacy {@code messages.yml} left over from an old install is moved into the
 * server's default language file on first startup; that way an update doesn't lose
 * text the server owner edited, and nobody has to delete a file by hand.</p>
 */
public final class LanguageManager {

    /** Languages bundled with the plugin. */
    /**
     * Languages bundled with the jar.
     *
     * <p>The server owner can add a 14th, 15th language by dropping their own
     * file into the {@code lang/} folder; {@link #load()} also scans every
     * {@code *.yml} file in that folder, so this list only means "what gets
     * extracted from the jar".</p>
     *
     * <p>Order doesn't matter, but {@code en} must always be here: missing
     * keys fall back to it.</p>
     */
    public static final List<String> BUNDLED = List.of(
            "en", "tr", "de", "es", "fr", "it", "pt", "ru", "pl", "nl", "cs", "zh", "ja");

    private final OrderPlugin plugin;

    private final Map<String, YamlConfiguration> overlays = new HashMap<>();   // code -> language file
    private final Map<String, String> localeMap = new HashMap<>();             // normalized locale -> code
    private final Map<UUID, String> overrides = new ConcurrentHashMap<>();     // player's own choice
    private final Map<String, String> cache = new ConcurrentHashMap<>();       // code\0key -> raw text

    /**
     * Prefix-added + minifont-applied + colorized text.
     *
     * <p>These three steps depend only on the language file and the config;
     * for a given key they produce the same result on every call. This used
     * to be redone from scratch on every {@link #msg} call: a single menu
     * opening ran the prefix lookup ({@code getConfig().getString}), the
     * minifont character conversion, and the hex-color regex over 300 times.
     * Now only the placeholder substitution is left to do each time.</p>
     *
     * <p>{@link #load()} clears this cache too, so stale text doesn't stick
     * around after {@code /reload}.</p>
     */
    private final Map<String, String> prepared = new ConcurrentHashMap<>();    // code\0key -> prepared text

    /** Fetched once at load so the prefix isn't read from config on every message. */
    private String prefix = "";

    private String serverDefault = "tr";
    private boolean perPlayer = true;

    public LanguageManager(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ loading

    /** Extracts the bundled files (if missing) and loads all languages into memory. */
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
            // Bring in new keys on a version upgrade. The merge happens in memory FIRST:
            // even if we can't write the file, the player must not see "missing text".
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

        // Extra languages that are on disk but not bundled (the server owner's own translation) are loaded too.
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
     * Moves a leftover {@code messages.yml} from an old version into a language file, <b>once</b>.
     *
     * <p>Treating the file as a permanent override layer would preserve the
     * server owner's edits, but it would also kill per-player language: a
     * player playing in English would still see the Turkish text from
     * messages.yml. So the content is written into the server's default
     * language instead, and the file is renamed to {@code messages.yml.migrated}.
     * That way no edit is lost, nobody has to delete a file by hand, and
     * languages don't get mixed up.</p>
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

    // ------------------------------------------------------------------ resolution

    /** The language code the recipient should use. */
    public String resolve(CommandSender sender) {
        if (perPlayer && sender instanceof Player p) {
            String override = overrides.get(p.getUniqueId());
            if (override != null && overlays.containsKey(override)) return override;
            String byLocale = fromLocale(p);
            if (byLocale != null) return byLocale;
        }
        return serverDefault;
    }

    /** Text with color codes applied; {@code %key%} pairs are substituted in order. */
    public String msg(CommandSender target, String key, String... replacements) {
        String text = prepared(resolve(target), key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        // Colorizing happens AFTER placeholder substitution, so that the
        // substituted text (e.g. an item name the server owner wrote in color) gets colorized too.
        return TextUtil.colorize(text);
    }

    /**
     * List-valued text (lore, sign lines, animation frames).
     *
     * <p>If the value is written as <b>plain text</b> in the file, it's treated
     * as a single-line list. When the server owner wrote {@code price: "Set the price"},
     * {@code isList} used to return false, the text was treated as absent, and
     * the sign opened BLANK; now the text they wrote shows up.</p>
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
     * Text with the prefix added and minifont applied; placeholders and color
     * codes are <b>not processed yet</b>. The result is cached.
     *
     * <p>Colorizing is left out because the value substituted into a placeholder
     * can carry color codes of its own; the cache only holds the part that
     * depends solely on the key (and therefore never changes).</p>
     */
    private String prepared(String code, String key) {
        return prepared.computeIfAbsent(code + '\0' + key, k -> {
            String raw = raw(code, key);
            raw = raw.replace("{prefix}", prefix);
            return miniFont(key, raw);
        });
    }

    /**
     * Applies the minifont conversion based on the <b>type</b> of the text.
     *
     * <p>The type is read off the language key's prefix, so no extra parameter
     * needs to be added to every call, and a newly added button behaves
     * correctly automatically:</p>
     * <ul>
     *   <li>{@code *.buttons.*} -> {@code text.minifont.buttons}</li>
     *   <li>{@code menus.*}     -> {@code text.minifont.titles}</li>
     *   <li>{@code *.lore.*}    -> {@code text.minifont.lore}</li>
     *   <li>everything else (chat messages) -> {@code text.minifont.messages}</li>
     * </ul>
     *
     * <p>Lore is kept separate from chat messages: button descriptions sit
     * right next to the button itself in the menu, and having one in small
     * caps while the other is normal looks inconsistent. Chat messages, on
     * the other hand, are long sentences that get harder to read once
     * converted to small caps — so those are off by default.</p>
     *
     * <p>The conversion happens <b>before</b> placeholder substitution: the
     * label turns into small caps, but the player name, price, and item name
     * written into it stay readable.</p>
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

    /** Whether the key is defined in this language (fallback included). */
    public boolean has(String key) {
        for (YamlConfiguration ov : overlays.values()) {
            if (ov.isSet(key)) return true;
        }
        return false;
    }

    /** Raw text — no color codes applied, no placeholders substituted. */
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
     * Same resolution as {@link #raw}, but does <b>not warn</b> if the key is
     * missing and returns {@code null} instead.
     *
     * <p>Some keys are normally absent: entries like {@code names.items.OAK_LOG}
     * — "optional full name" overrides — are deliberately left blank in the
     * language files and are generated from the word dictionary instead.
     * {@link #raw} used to print a warning for every one of them — filling the
     * console with ~1300 lines the first time the item-selection menu opened,
     * each one written to disk synchronously.</p>
     */
    public String rawOrNull(String code, String key) {
        return lookup(code, key);
    }

    /** Looks up in order: the player's language -> English -> Turkish; null if in none of them. */
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
     * List-valued raw text — no color codes applied, no placeholders substituted,
     * and looked up <b>only in the given language</b> (no en/tr fallback).
     *
     * <p>If the value is written as plain text, a single-element list is
     * returned — the server owner switching formats doesn't lose the text.
     * If the key doesn't exist at all, {@code null} is returned; the caller
     * (e.g. lore templates) can interpret that as "not defined in this
     * language, check another one or fall back to old code".</p>
     */
    public List<String> rawList(String code, String key) {
        YamlConfiguration ov = overlays.get(code);
        if (ov == null) return null;
        if (ov.isList(key)) return ov.getStringList(key);
        String single = ov.getString(key);
        return single == null ? null : List.of(single);
    }

    /** The keys under a section — for loading a dictionary. */
    public ConfigurationSection section(String code, String path) {
        YamlConfiguration ov = overlays.get(code);
        return ov == null ? null : ov.getConfigurationSection(path);
    }

    // ------------------------------------------------------------------ player choice

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

    /** The language's own name (from {@code language.name} in the lang file), for showing in menus. */
    public String displayName(String code) {
        YamlConfiguration ov = overlays.get(code);
        String name = ov == null ? null : ov.getString("language.name");
        return name != null ? name : code;
    }

    // ------------------------------------------------------------------ helpers

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
     * Adds keys that exist in the jar but not on disk; does NOT touch existing ones.
     *
     * @return number of keys added
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
