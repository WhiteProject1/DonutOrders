package ro.server.orderplugin.menu;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import ro.server.orderplugin.OrderPlugin;

/**
 * Loads all GUI layouts from the {@code menus/} folder.
 *
 * <p>Each menu has its own file. Changing one menu doesn't affect the others, and
 * fields added in a new version are added to existing files <b>without overwriting</b>
 * (see {@link #mergeMissing}) — the server owner never needs to delete their config.</p>
 */
public final class MenuRegistry {

    // Menu ids — referenced by these constants everywhere in the code.
    public static final String MAIN_MENU = "main-menu";
    public static final String YOUR_ORDERS = "your-orders";
    public static final String NEW_ORDER = "new-order";
    public static final String ITEM_SELECTOR = "item-selector";
    public static final String DELIVER_ITEMS = "deliver-items";
    public static final String CONFIRM_DELIVERY = "confirm-delivery";
    public static final String CONFIRM_SELL = "confirm-sell";
    public static final String EDIT_ORDER = "edit-order";
    public static final String COLLECT_ITEMS = "collect-items";
    public static final String ENCHANTMENT_PICKER = "enchantment-picker";
    public static final String POTION_SELECTOR = "potion-selector";
    public static final String LANGUAGE = "language";
    public static final String ADMIN_MENU = "admin-menu";
    public static final String ADMIN_ORDERS = "admin-orders";

    /** Menu definition: default row count and the buttons the code knows about. */
    private record Definition(int rows, Map<String, Material> buttons) {}

    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();

    static {
        DEFINITIONS.put(MAIN_MENU, new Definition(6, buttons(
                "previous-page", Material.ARROW,
                "next-page", Material.ARROW,
                "sort", Material.CAULDRON,
                "filter", Material.HOPPER,
                "refresh", Material.PAPER,
                "search", Material.OAK_SIGN,
                "your-orders", Material.CHEST,
                "language", Material.BOOK,
                "level", Material.EXPERIENCE_BOTTLE)));

        DEFINITIONS.put(YOUR_ORDERS, new Definition(4, buttons(
                "back", Material.RED_STAINED_GLASS_PANE,
                "new-order", Material.PAPER)));

        DEFINITIONS.put(NEW_ORDER, new Definition(3, buttons(
                "cancel", Material.RED_STAINED_GLASS_PANE,
                "item", Material.STONE,
                "amount", Material.CHEST,
                "price", Material.EMERALD,
                "confirm", Material.LIME_STAINED_GLASS_PANE)));

        DEFINITIONS.put(ITEM_SELECTOR, new Definition(6, buttons(
                "previous-page", Material.ARROW,
                "back", Material.BARRIER,
                "next-page", Material.ARROW,
                "search", Material.OAK_SIGN)));

        DEFINITIONS.put(DELIVER_ITEMS, new Definition(4, buttons(
                "quick-fill", Material.HOPPER)));

        DEFINITIONS.put(CONFIRM_DELIVERY, new Definition(3, buttons(
                "cancel", Material.RED_STAINED_GLASS_PANE,
                "preview", Material.STONE,
                "confirm", Material.LIME_STAINED_GLASS_PANE)));

        DEFINITIONS.put(CONFIRM_SELL, new Definition(3, buttons(
                "cancel", Material.RED_STAINED_GLASS_PANE,
                "preview", Material.STONE,
                "confirm", Material.LIME_STAINED_GLASS_PANE)));

        DEFINITIONS.put(EDIT_ORDER, new Definition(3, buttons(
                "order", Material.STONE,
                "cancel-order", Material.RED_TERRACOTTA,
                "collect-items", Material.CHEST,
                "back", Material.RED_STAINED_GLASS_PANE)));

        DEFINITIONS.put(COLLECT_ITEMS, new Definition(6, buttons(
                "previous-page", Material.ARROW,
                "next-page", Material.ARROW,
                "sell-all", Material.EMERALD,
                "drop-all", Material.DISPENSER)));

        DEFINITIONS.put(ENCHANTMENT_PICKER, new Definition(6, buttons(
                "preview", Material.STONE,
                "previous-page", Material.ARROW,
                "next-page", Material.ARROW,
                "cancel", Material.RED_STAINED_GLASS_PANE,
                "confirm", Material.LIME_STAINED_GLASS_PANE)));

        DEFINITIONS.put(POTION_SELECTOR, new Definition(6, buttons(
                "back", Material.BARRIER)));

        DEFINITIONS.put(ADMIN_MENU, new Definition(5, buttons(
                "reload", Material.REDSTONE,
                "orders", Material.CHEST,
                "stats", Material.PAPER,
                "cleanup", Material.LAVA_BUCKET,
                "features", Material.COMPARATOR,
                "tax", Material.GOLD_INGOT,
                "levels", Material.EXPERIENCE_BOTTLE,
                "close", Material.BARRIER)));

        DEFINITIONS.put(ADMIN_ORDERS, new Definition(6, buttons(
                "previous-page", Material.ARROW,
                "back", Material.BARRIER,
                "next-page", Material.ARROW,
                "search", Material.OAK_SIGN)));

        DEFINITIONS.put(LANGUAGE, new Definition(5, buttons(
                "back", Material.BARRIER,
                "previous-page", Material.ARROW,
                "next-page", Material.ARROW)));
    }

    private static Map<String, Material> buttons(Object... pairs) {
        Map<String, Material> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], (Material) pairs[i + 1]);
        }
        return map;
    }

    private final OrderPlugin plugin;
    private final Map<String, MenuConfig> menus = new ConcurrentHashMap<>();

    public MenuRegistry(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        menus.clear();
        File dir = new File(plugin.getDataFolder(), "menus");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("menus/ klasoru olusturulamadi: " + dir);
        }

        int addedTotal = 0;
        for (Map.Entry<String, Definition> entry : DEFINITIONS.entrySet()) {
            String id = entry.getKey();
            File file = new File(dir, id + ".yml");
            YamlConfiguration bundled = loadBundled(id);

            if (!file.exists()) {
                extractIfMissing(dir, id);
            }

            FileConfiguration config;
            if (file.exists()) {
                YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
                int added = mergeMissing(onDisk, bundled);
                if (added > 0) {
                    addedTotal += added;
                    try {
                        onDisk.save(file);
                    } catch (Exception e) {
                        plugin.getLogger().warning("menus/" + id + ".yml guncellenemedi (" + e.getMessage()
                                + "); yeni alanlar yalnizca bu oturum icin gecerli.");
                    }
                }
                config = onDisk;
            } else if (bundled != null) {
                config = bundled;
            } else {
                plugin.getLogger().severe("menus/" + id + ".yml ne diskte ne jar'da bulunabildi!");
                continue;
            }

            menus.put(id, MenuConfig.parse(id, config, entry.getValue().rows(), entry.getValue().buttons(),
                    msg -> plugin.getLogger().warning("[menu] " + msg)));
        }

        if (addedTotal > 0) {
            plugin.getLogger().info(addedTotal + " yeni menu ayari mevcut dosyalara eklendi (surum yukseltmesi).");
        }
        plugin.getLogger().info(menus.size() + " menu yerlesimi yuklendi.");
    }

    public MenuConfig get(String id) {
        MenuConfig config = menus.get(id);
        if (config == null) {
            throw new IllegalStateException("Menu yuklenmemis: " + id);
        }
        return config;
    }

    // ------------------------------------------------------------------ helpers

    private YamlConfiguration loadBundled(String id) {
        try (InputStream in = plugin.getResource("menus/" + id + ".yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Paketli menus/" + id + ".yml okunamadi: " + e.getMessage());
            return null;
        }
    }

    private void extractIfMissing(File dir, String id) {
        File out = new File(dir, id + ".yml");
        if (out.exists()) return;
        try (InputStream in = plugin.getResource("menus/" + id + ".yml")) {
            if (in != null) java.nio.file.Files.copy(in, out.toPath());
        } catch (Exception e) {
            plugin.getLogger().warning("menus/" + id + ".yml cikarilamadi: " + e.getMessage());
        }
    }

    /**
     * Adds settings that exist in the jar but not on disk; leaves existing ones untouched.
     *
     * <p>There's a deliberate exception for a server owner who wants to <b>delete</b> a
     * button: if the section itself still exists on disk (e.g. the {@code items.search}
     * section is there but a new field inside it is missing), only the missing field is
     * added. If the whole section was deleted, it does not come back — deletion is permanent.</p>
     */
    private static int mergeMissing(YamlConfiguration onDisk, YamlConfiguration bundled) {
        if (bundled == null) return 0;
        int added = 0;
        for (String path : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(path)) continue;
            if (onDisk.contains(path)) continue;
            // Don't bring back a field whose parent section was fully deleted.
            int dot = path.lastIndexOf('.');
            if (dot > 0 && !onDisk.contains(path.substring(0, dot))) continue;
            onDisk.set(path, bundled.get(path));
            added++;
        }
        return added;
    }
}
