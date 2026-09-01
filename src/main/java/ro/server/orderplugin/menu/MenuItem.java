package ro.server.orderplugin.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import ro.server.orderplugin.util.SoundSpec;

/**
 * A single button/filler definition inside {@code menus/*.yml}.
 *
 * <p>Text (name/lore) normally comes from the language file; the {@code name} and
 * {@code lore} fields here are <b>optional</b> overrides. Once an override is used,
 * the text no longer changes with the player's language — this is deliberate, so a
 * server owner can say "this button always shows this text".</p>
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

    /** For buttons undefined in the config: disabled, no slots. */
    public static MenuItem disabled(String key) {
        return new MenuItem(key, false, new int[0], null, 0, false, null, null, SoundSpec.NONE, false);
    }

    /**
     * @param fallbackMaterial material to use if the config has no {@code material}
     *                         (the default the code expects)
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
        if (name != null && name.isEmpty()) name = " "; // deliberately left-blank name

        List<String> lore = section.isList("lore") ? section.getStringList("lore") : null;
        if (lore != null && lore.isEmpty()) lore = List.of();

        // sound-volume / sound-pitch are the old (2.0) format and still work;
        // the single-line "key:volume:pitch" format overrides them.
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
     * Accepts all of {@code slot: 13}, {@code slots: [45, 46]}, and
     * {@code slots: "0-44, 53"} as valid formats.
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

    // ------------------------------------------------------------------ access

    public String key() { return key; }
    public boolean enabled() { return enabled; }
    public boolean hasSlots() { return slots.length > 0; }
    public int[] slots() { return slots; }
    /**
     * The first slot; -1 if no slot is defined.
     *
     * <p>-1 means "auto placement" for some buttons (e.g. "New Order" on the My
     * Orders screen), so a negative value isn't treated as an error.</p>
     */
    public int slot() { return slots.length == 0 ? -1 : slots[0]; }
    public Material material() { return material; }
    public int customModelData() { return customModelData; }
    public boolean glow() { return glow; }
    public String nameOverride() { return nameOverride; }
    public List<String> loreOverride() { return loreOverride; }
    /** Click sound; never null ({@link SoundSpec#NONE} = silent). */
    public SoundSpec sound() { return sound; }
    /**
     * Only meaningful for filler: if true, it also overwrites an occupied slot.
     *
     * <p>Defaults to false. Filler used to always overwrite, and a range like
     * "45-53" would hide the page/back/search buttons.</p>
     */
    public boolean overwrite() { return overwrite; }

    /** Does the given slot belong to this button? */
    public boolean matches(int slot) {
        for (int s : slots) {
            if (s == slot) return true;
        }
        return false;
    }
}
