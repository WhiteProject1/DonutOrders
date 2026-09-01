package ro.server.orderplugin.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import ro.server.orderplugin.OrderPlugin;

/**
 * Adds new settings to {@code config.yml} on a version upgrade.
 *
 * <p>No existing value is ever touched — only keys that are <b>entirely absent</b>
 * from the file are taken from the default bundled in the jar. The goal is that
 * a server owner should never end up having to "delete the config and set it up
 * from scratch" after updating the plugin.</p>
 *
 * <p>Since Bukkit's YamlConfiguration has preserved comment lines when saving
 * since 1.18, existing comments aren't lost either; only newly added keys come
 * in without comments, and which ones were added is logged to the console.</p>
 *
 * <h2>Why this behaves differently from {@code menus/*.yml}</h2>
 *
 * <p>{@link ro.server.orderplugin.menu.MenuRegistry} does not bring back a
 * deleted <b>section</b>: there, deleting a button means "this button shouldn't
 * exist", and having it reappear would undo the server owner's decision.</p>
 *
 * <p>The same rule would backfire in config.yml. None of the {@code tax},
 * {@code text}, {@code custom-items}, or {@code network} sections added in 3.0
 * exist in an old file; a "don't add if the parent section is missing" rule
 * would mean <b>none</b> of them ever get added, and the server owner could
 * only configure the new features by deleting their config — exactly the
 * situation this is meant to avoid. Also, in config.yml the absence of a
 * setting and its default value mean the same thing, so adding a missing key
 * doesn't change behavior; it just makes the setting visible and editable.</p>
 */
public final class ConfigUpdater {

    private ConfigUpdater() {}

    public static void update(OrderPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) return; // saveDefaultConfig already wrote the complete file

        YamlConfiguration bundled = loadBundled(plugin);
        if (bundled == null) return;

        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
        boolean migrated = migrate(plugin, onDisk);

        List<String> added = new ArrayList<>();
        for (String path : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(path)) continue;
            if (onDisk.contains(path)) continue;
            onDisk.set(path, bundled.get(path));
            added.add(path);
        }

        if (added.isEmpty() && !migrated) return;

        try {
            onDisk.save(file);
            if (!added.isEmpty()) {
                plugin.getLogger().info("config.yml updated, new settings added: " + String.join(", ", added));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("config.yml could not be updated (" + e.getMessage()
                    + "); new settings will use their default values.");
        }
    }

    /**
     * Moves settings whose location has changed — so the server owner's value isn't lost.
     *
     * <p>Only a <b>move</b> is performed: the old key is removed, its value is
     * written to the new location. If the new location already has a value, the
     * old one is discarded, because in that case the server owner has deliberately
     * filled in the new setting.</p>
     *
     * @return true if there's a change that needs to be written to the file
     */
    private static boolean migrate(OrderPlugin plugin, YamlConfiguration onDisk) {
        boolean changed = false;

        // 2.0 -> 3.0: sounds.error / sounds.success now live under sounds.events.
        changed |= moveString(plugin, onDisk, "sounds.error", "sounds.events.error");
        changed |= moveString(plugin, onDisk, "sounds.success", "sounds.events.success");

        // 2.0 -> 3.0: tax moved from under orders. to tax. AND the rate format
        // changed (0.05 -> 5.0). Without this conversion, a server wanting 5%
        // would charge 0.05% instead; rather than fail silently, we migrate it.
        if (onDisk.isSet("orders.creation-tax-percent") && !onDisk.isSet("tax.creation-percent")) {
            double old = onDisk.getDouble("orders.creation-tax-percent", 0d);
            onDisk.set("tax.creation-percent", old * 100d);
            // If the rate was 0 in the old file, the server owner didn't want tax: don't turn it on.
            if (!onDisk.isSet("tax.enabled")) onDisk.set("tax.enabled", old > 0d);
            onDisk.set("orders.creation-tax-percent", null);
            plugin.getLogger().info("config.yml: tax orders.creation-tax-percent ("
                    + old + ") -> tax.creation-percent (" + (old * 100d) + "%) migrated.");
            changed = true;
        }
        if (onDisk.isConfigurationSection("orders.rank-tax") && !onDisk.isSet("tax.rank-rates")) {
            ConfigurationSection old = onDisk.getConfigurationSection("orders.rank-tax");
            for (String key : old.getKeys(false)) {
                onDisk.set("tax.rank-rates." + key, old.getDouble(key) * 100d);
            }
            onDisk.set("orders.rank-tax", null);
            plugin.getLogger().info("config.yml: orders.rank-tax -> tax.rank-rates migrated (converted to percentage).");
            changed = true;
        }

        return changed;
    }

    private static boolean moveString(OrderPlugin plugin, YamlConfiguration config, String from, String to) {
        if (!config.isString(from)) return false;
        String value = config.getString(from);
        config.set(from, null);
        if (config.isSet(to)) {
            plugin.getLogger().info("config.yml: '" + from + "' removed ('" + to + "' already defined).");
            return true;
        }
        config.set(to, value);
        plugin.getLogger().info("config.yml: '" + from + "' -> '" + to + "' migrated (value preserved: " + value + ").");
        return true;
    }

    private static YamlConfiguration loadBundled(OrderPlugin plugin) {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read the bundled config.yml: " + e.getMessage());
            return null;
        }
    }
}
