package ro.server.orderplugin.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import ro.server.orderplugin.util.SoundSpec;

/**
 * Tek bir GUI'nin yerlesimi: satir sayisi, icerik slotlari, dolgu ve butonlar.
 *
 * <p>Baslik metni burada tutulmaz — o dil dosyasindan gelir ki her oyuncu kendi
 * dilinde gorsun. Menu dosyasindaki {@code title} yalnizca bir ezmedir.</p>
 */
public final class MenuConfig {

    private final String id;
    private final int rows;
    private final String titleOverride;
    private final int[] contentSlots;
    private final MenuItem filler;
    private final Map<String, MenuItem> items;
    private final SoundSpec openSound;
    private final SoundSpec closeSound;
    private final FileConfiguration raw;

    private MenuConfig(String id, int rows, String titleOverride, int[] contentSlots,
                       MenuItem filler, Map<String, MenuItem> items,
                       SoundSpec openSound, SoundSpec closeSound,
                       FileConfiguration raw) {
        this.id = id;
        this.rows = rows;
        this.titleOverride = titleOverride;
        this.contentSlots = contentSlots;
        this.filler = filler;
        this.items = items;
        this.openSound = openSound;
        this.closeSound = closeSound;
        this.raw = raw;
    }

    /**
     * @param defaults kodun bekledigi varsayilanlar: buton anahtari -> materyal.
     *                 Config'de materyal yazilmamissa bu kullanilir, boylece
     *                 eksik bir alan menuyu bozmaz.
     * @param defaultRows dosyada {@code rows} yoksa kullanilacak satir sayisi
     */
    public static MenuConfig parse(String id, FileConfiguration config, int defaultRows,
                                   Map<String, Material> defaults,
                                   java.util.function.Consumer<String> warn) {
        int rows = clampRows(config.getInt("rows", defaultRows), warn, id, defaultRows);

        String title = config.getString("title");
        if (title != null && title.isBlank()) title = null;

        int[] contentSlots = parseContentSlots(config.getString("content-slots"), rows, warn, id);

        MenuItem filler = MenuItem.parse("filler", config.getConfigurationSection("filler"),
                Material.GRAY_STAINED_GLASS_PANE, warn);

        Map<String, MenuItem> items = new HashMap<>();
        ConfigurationSection itemsSection = config.getConfigurationSection("items");
        for (Map.Entry<String, Material> entry : defaults.entrySet()) {
            ConfigurationSection sec = itemsSection == null ? null : itemsSection.getConfigurationSection(entry.getKey());
            if (sec == null) {
                // Butonu kod biliyor ama config'de yok: kapali say. Sunucu sahibi bir
                // butonu dosyadan silerek kaldirabilsin diye bilerek boyle.
                items.put(entry.getKey(), MenuItem.disabled(entry.getKey()));
                continue;
            }
            items.put(entry.getKey(), MenuItem.parse(entry.getKey(), sec, entry.getValue(), warn));
        }
        // Config'de olup kodun tanimadigi butonlar: uyar, yok say.
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                if (!defaults.containsKey(key)) {
                    warn.accept("menus/" + id + ".yml icinde taninmayan buton '" + key + "', yok sayildi.");
                }
            }
        }

        SoundSpec openSound = SoundSpec.parse(config.getString("open-sound"), SoundSpec.NONE);
        SoundSpec closeSound = SoundSpec.parse(config.getString("close-sound"), SoundSpec.NONE);

        return new MenuConfig(id, rows, title, contentSlots, filler, items, openSound, closeSound, config);
    }

    private static int clampRows(int rows, java.util.function.Consumer<String> warn, String id, int fallback) {
        if (rows < 1 || rows > 6) {
            warn.accept("menus/" + id + ".yml -> rows " + rows + " gecersiz (1-6 olmali), " + fallback + " kullaniliyor.");
            return fallback;
        }
        return rows;
    }

    private static int[] parseContentSlots(String raw, int rows, java.util.function.Consumer<String> warn, String id) {
        List<Integer> out = new ArrayList<>();
        int size = rows * 9;
        if (raw == null || raw.isBlank()) {
            return new int[0];
        }
        for (String piece : raw.split(",")) {
            String p = piece.trim();
            if (p.isEmpty()) continue;
            try {
                int dash = p.indexOf('-');
                if (dash > 0) {
                    int from = Integer.parseInt(p.substring(0, dash).trim());
                    int to = Integer.parseInt(p.substring(dash + 1).trim());
                    for (int i = Math.min(from, to); i <= Math.max(from, to); i++) out.add(i);
                } else {
                    out.add(Integer.parseInt(p));
                }
            } catch (NumberFormatException e) {
                warn.accept("menus/" + id + ".yml -> content-slots icinde gecersiz deger '" + p + "'.");
            }
        }
        List<Integer> valid = new ArrayList<>(out.size());
        for (int slot : out) {
            if (slot < 0 || slot >= size) {
                warn.accept("menus/" + id + ".yml -> content-slots slot " + slot + " envanter disinda (0-" + (size - 1) + "), atlandi.");
                continue;
            }
            valid.add(slot);
        }
        int[] result = new int[valid.size()];
        for (int i = 0; i < result.length; i++) result[i] = valid.get(i);
        return result;
    }

    // ------------------------------------------------------------------ erisim

    public String id() { return id; }
    public int rows() { return rows; }
    public int size() { return rows * 9; }
    public String titleOverride() { return titleOverride; }
    public int[] contentSlots() { return contentSlots; }
    /** Sayfa basina icerik slotu sayisi. */
    public int pageSize() { return contentSlots.length; }
    public MenuItem filler() { return filler; }
    public SoundSpec openSound() { return openSound; }
    public SoundSpec closeSound() { return closeSound; }

    public MenuItem item(String key) {
        MenuItem item = items.get(key);
        return item != null ? item : MenuItem.disabled(key);
    }

    public Map<String, MenuItem> items() {
        return Collections.unmodifiableMap(items);
    }

    /**
     * Menu dosyasinin ham hali — yalnizca tek bir menuye ozel ek bolumler icin
     * (orn. dil menusundeki {@code icons}). Genel ayarlar icin yukaridaki
     * yardimcilar kullanilmali.
     */
    public FileConfiguration raw() {
        return raw;
    }

    /** Verilen slotu kullanan butonun anahtari; yoksa null. */
    public String buttonAt(int slot) {
        for (MenuItem item : items.values()) {
            if (item.enabled() && item.hasSlots() && item.matches(slot)) return item.key();
        }
        return null;
    }

    /** {@code contentSlots} icindeki sirasi; slot icerik alaninda degilse -1. */
    public int contentIndexOf(int slot) {
        for (int i = 0; i < contentSlots.length; i++) {
            if (contentSlots[i] == slot) return i;
        }
        return -1;
    }
}
