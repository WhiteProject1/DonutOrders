package ro.server.orderplugin.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import ro.server.orderplugin.util.SoundSpec;

/**
 * {@code menus/*.yml} icindeki tek bir buton/dolgu tanimi.
 *
 * <p>Metin (isim/lore) normalde dil dosyasindan gelir; buradaki {@code name} ve
 * {@code lore} alanlari <b>istege bagli</b> ezmelerdir. Ezme kullanildiginda metin
 * artik oyuncunun diline gore degismez — bu bilincli bir tercihtir: sunucu sahibi
 * "bu butonda her zaman sunu yazsin" diyebilsin diye.</p>
 */
public final class MenuItem {

    private final String key;
    private final boolean enabled;
    private final int[] slots;
    private final Material material;
    private final int customModelData;
    private final boolean glow;
    private final String nameOverride;
    private final List<String> loreOverride;
    private final SoundSpec sound;
    private final boolean overwrite;

    private MenuItem(String key, boolean enabled, int[] slots, Material material, int customModelData,
                     boolean glow, String nameOverride, List<String> loreOverride,
                     SoundSpec sound, boolean overwrite) {
        this.key = key;
        this.enabled = enabled;
        this.slots = slots;
        this.material = material;
        this.customModelData = customModelData;
        this.glow = glow;
        this.nameOverride = nameOverride;
        this.loreOverride = loreOverride;
        this.sound = sound;
        this.overwrite = overwrite;
    }

    /** Config'de tanimsiz butonlar icin: kapali, slot yok. */
    public static MenuItem disabled(String key) {
        return new MenuItem(key, false, new int[0], null, 0, false, null, null, SoundSpec.NONE, false);
    }

    /**
     * @param fallbackMaterial config'de {@code material} yoksa kullanilacak materyal
     *                         (kodun bekledigi varsayilan)
     */
    public static MenuItem parse(String key, ConfigurationSection section, Material fallbackMaterial,
                                 java.util.function.Consumer<String> warn) {
        if (section == null) {
            return new MenuItem(key, true, new int[0], fallbackMaterial, 0, false, null, null,
                    SoundSpec.NONE, false);
        }

        boolean enabled = section.getBoolean("enabled", true);
        int[] slots = parseSlots(section, warn, key);

        Material material = fallbackMaterial;
        String rawMaterial = section.getString("material");
        if (rawMaterial != null && !rawMaterial.isBlank()) {
            Material parsed = Material.matchMaterial(rawMaterial.trim().toUpperCase(Locale.ROOT));
            if (parsed == null) {
                warn.accept("Bilinmeyen materyal '" + rawMaterial + "' (" + key + "), varsayilan kullaniliyor.");
            } else {
                material = parsed;
            }
        }

        String name = section.getString("name");
        if (name != null && name.isEmpty()) name = " "; // bilerek bos birakilan isim

        List<String> lore = section.isList("lore") ? section.getStringList("lore") : null;
        if (lore != null && lore.isEmpty()) lore = List.of();

        // sound-volume / sound-pitch eski (2.0) yazim bicimidir ve hala calisir;
        // "anahtar:seviye:perde" tek satirli bicim onlarin uzerine yazar.
        SoundSpec sound = SoundSpec.parse(section.getString("sound"), SoundSpec.NONE,
                (float) section.getDouble("sound-volume", 1.0),
                (float) section.getDouble("sound-pitch", 1.0));

        return new MenuItem(key, enabled, slots, material,
                section.getInt("custom-model-data", 0),
                section.getBoolean("glow", false),
                name, lore, sound,
                section.getBoolean("overwrite", false));
    }

    /**
     * {@code slot: 13}, {@code slots: [45, 46]} ve {@code slots: "0-44, 53"}
     * bicimlerinin hepsini kabul eder.
     */
    private static int[] parseSlots(ConfigurationSection section, java.util.function.Consumer<String> warn, String key) {
        List<Integer> out = new ArrayList<>();
        if (section.isInt("slot")) {
            out.add(section.getInt("slot"));
        }
        Object raw = section.get("slots");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) out.add(n.intValue());
                else if (o != null) parseRange(o.toString(), out, warn, key);
            }
        } else if (raw != null) {
            parseRange(raw.toString(), out, warn, key);
        }
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

    private static void parseRange(String raw, List<Integer> out, java.util.function.Consumer<String> warn, String key) {
        for (String piece : raw.split(",")) {
            String p = piece.trim();
            if (p.isEmpty()) continue;
            try {
                int dash = p.indexOf('-');
                if (dash > 0) {
                    int from = Integer.parseInt(p.substring(0, dash).trim());
                    int to = Integer.parseInt(p.substring(dash + 1).trim());
                    if (from > to) { int t = from; from = to; to = t; }
                    for (int i = from; i <= to; i++) out.add(i);
                } else {
                    out.add(Integer.parseInt(p));
                }
            } catch (NumberFormatException e) {
                warn.accept("Gecersiz slot ifadesi '" + p + "' (" + key + "), atlandi.");
            }
        }
    }

    // ------------------------------------------------------------------ erisim

    public String key() { return key; }
    public boolean enabled() { return enabled; }
    public boolean hasSlots() { return slots.length > 0; }
    public int[] slots() { return slots; }
    /**
     * Ilk slot; tanimli slot yoksa -1.
     *
     * <p>-1 bazi butonlarda "otomatik yerlesim" anlamina gelir (orn. Siparislerim
     * ekranindaki "Yeni Siparis"), bu yuzden negatif deger hatali sayilmaz.</p>
     */
    public int slot() { return slots.length == 0 ? -1 : slots[0]; }
    public Material material() { return material; }
    public int customModelData() { return customModelData; }
    public boolean glow() { return glow; }
    public String nameOverride() { return nameOverride; }
    public List<String> loreOverride() { return loreOverride; }
    /** Tiklama sesi; hicbir zaman null degildir ({@link SoundSpec#NONE} = sessiz). */
    public SoundSpec sound() { return sound; }
    /**
     * Yalnizca dolgu icin anlamli: true ise dolu bir slotun uzerine de yazar.
     *
     * <p>Varsayilan false. Eskiden dolgu her zaman ezerdi ve "45-53" gibi bir aralik
     * sayfa/geri/arama butonlarini gorunmez yapardi.</p>
     */
    public boolean overwrite() { return overwrite; }

    /** Verilen slot bu butona mi ait? */
    public boolean matches(int slot) {
        for (int s : slots) {
            if (s == slot) return true;
        }
        return false;
    }
}
