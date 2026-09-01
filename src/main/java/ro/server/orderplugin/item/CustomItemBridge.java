package ro.server.orderplugin.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import ro.server.orderplugin.OrderPlugin;

/**
 * Bridges to custom item plugins (ItemsAdder / Oraxen / Nexo) and to vanilla
 * {@code custom-model-data} items.
 *
 * <p>None of the providers are added to {@code pom.xml} as a compile
 * dependency; all of them are invoked via <b>reflection</b>. This way the
 * plugin still works with none of these three plugins installed, and the
 * server doesn't error out when one of them is removed.</p>
 *
 * <p>Identity format: {@code provider:key} — e.g. {@code itemsadder:ruby},
 * {@code oraxen:mythic_sword}, {@code cmd:DIAMOND_SWORD:10001}.</p>
 */
public final class CustomItemBridge {

    /** A single custom item source. */
    public interface Provider {
        String id();

        /** Is the plugin installed and its API reachable? */
        boolean available();

        /** Builds an item from a key; null if not found. */
        ItemStack build(String key);

        /** The item's key if it belongs to this provider, null otherwise. */
        String keyOf(ItemStack stack);
    }

    private final OrderPlugin plugin;
    private final Map<String, Provider> providers = new LinkedHashMap<>();

    private boolean enabled = true;
    private String placement = "TOP";
    private boolean strictMatch = true;
    private List<String> whitelist = List.of();
    private List<String> blacklist = List.of();

    public CustomItemBridge(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        providers.clear();
        enabled = plugin.getConfig().getBoolean("custom-items.enabled", true);
        placement = plugin.getConfig().getString("custom-items.placement", "TOP")
                .trim().toUpperCase(Locale.ROOT);
        strictMatch = "STRICT".equalsIgnoreCase(
                plugin.getConfig().getString("custom-items.match-mode", "STRICT"));
        whitelist = lower(plugin.getConfig().getStringList("custom-items.whitelist"));
        blacklist = lower(plugin.getConfig().getStringList("custom-items.blacklist"));

        if (!enabled) {
            plugin.getLogger().info("Custom item support is disabled (custom-items.enabled: false).");
            return;
        }

        register("itemsadder", new ItemsAdderProvider());
        register("oraxen", new OraxenProvider());
        register("nexo", new NexoProvider());
        register("cmd", new ModelDataProvider());

        if (providers.isEmpty()) {
            plugin.getLogger().info("No custom item provider found; vanilla items only.");
        } else {
            plugin.getLogger().info("Custom item providers: " + providers.keySet());
        }
    }

    private void register(String configKey, Provider provider) {
        if (!plugin.getConfig().getBoolean("custom-items.providers." + configKey, true)) return;
        if (!provider.available()) return;
        providers.put(provider.id(), provider);
    }

    private static List<String> lower(List<String> input) {
        List<String> out = new ArrayList<>(input.size());
        for (String value : input) {
            if (value != null && !value.isBlank()) out.add(value.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ access

    public boolean enabled() {
        return enabled && !providers.isEmpty();
    }

    public boolean placeOnTop() {
        return "TOP".equals(placement);
    }

    public boolean placeAtBottom() {
        return "BOTTOM".equals(placement);
    }

    /** {@code provider:key} -> item; null if not found. */
    public ItemStack build(String fullKey) {
        if (!enabled() || fullKey == null) return null;
        int colon = fullKey.indexOf(':');
        if (colon <= 0) return null;
        Provider provider = providers.get(fullKey.substring(0, colon).toLowerCase(Locale.ROOT));
        if (provider == null) return null;
        try {
            return provider.build(fullKey.substring(colon + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** The item's custom identity; null if it's not a custom item. */
    public String identify(ItemStack stack) {
        if (!enabled() || stack == null) return null;
        for (Provider provider : providers.values()) {
            try {
                String key = provider.keyOf(stack);
                if (key != null && !key.isBlank()) return provider.id() + ":" + key;
            } catch (Exception ignored) {
                // If a provider's API changed, keep going with the others.
            }
        }
        return null;
    }

    /** Can orders be placed for this identity? */
    public boolean allowed(String fullKey) {
        if (fullKey == null) return true;
        String key = fullKey.toLowerCase(Locale.ROOT);
        if (blacklist.contains(key)) return false;
        return whitelist.isEmpty() || whitelist.contains(key);
    }

    /**
     * Does the delivered item match the order?
     *
     * <p>In STRICT mode, name and model data are compared too; in LOOSE mode
     * the custom identity alone is enough (a worn or renamed item is accepted).</p>
     */
    public boolean matches(String orderCustomId, ItemStack stack) {
        String stackId = identify(stack);
        if (orderCustomId == null) return stackId == null;
        if (!orderCustomId.equals(stackId)) return false;
        if (!strictMatch) return true;

        ItemStack reference = build(orderCustomId);
        if (reference == null) return true;   // couldn't build a reference: identity is enough
        return sameMeta(reference, stack);
    }

    private static boolean sameMeta(ItemStack a, ItemStack b) {
        ItemMeta ma = a.getItemMeta();
        ItemMeta mb = b.getItemMeta();
        if (ma == null || mb == null) return ma == mb;
        if (ma.hasCustomModelData() != mb.hasCustomModelData()) return false;
        if (ma.hasCustomModelData() && ma.getCustomModelData() != mb.getCustomModelData()) return false;
        if (ma.hasDisplayName() != mb.hasDisplayName()) return false;
        return !ma.hasDisplayName() || ma.getDisplayName().equals(mb.getDisplayName());
    }

    // ================================================================== providers

    /** Can the installed plugin's class actually be loaded? */
    private static boolean hasClass(String pluginName, String className) {
        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) return false;
        try {
            Class.forName(className);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** ItemsAdder — {@code dev.lone.itemsadder.api.CustomStack}. */
    private static final class ItemsAdderProvider implements Provider {
        @Override public String id() { return "itemsadder"; }

        @Override public boolean available() {
            return hasClass("ItemsAdder", "dev.lone.itemsadder.api.CustomStack");
        }

        @Override public ItemStack build(String key) {
            try {
                Class<?> clazz = Class.forName("dev.lone.itemsadder.api.CustomStack");
                Object instance = clazz.getMethod("getInstance", String.class).invoke(null, key);
                if (instance == null) return null;
                return (ItemStack) clazz.getMethod("getItemStack").invoke(instance);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public String keyOf(ItemStack stack) {
            try {
                Class<?> clazz = Class.forName("dev.lone.itemsadder.api.CustomStack");
                Object instance = clazz.getMethod("byItemStack", ItemStack.class).invoke(null, stack);
                if (instance == null) return null;
                return (String) clazz.getMethod("getNamespacedID").invoke(instance);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Oraxen — {@code io.th0rgal.oraxen.api.OraxenItems}. */
    private static final class OraxenProvider implements Provider {
        @Override public String id() { return "oraxen"; }

        @Override public boolean available() {
            return hasClass("Oraxen", "io.th0rgal.oraxen.api.OraxenItems");
        }

        @Override public ItemStack build(String key) {
            try {
                Class<?> clazz = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
                Object builder = clazz.getMethod("getItemById", String.class).invoke(null, key);
                if (builder == null) return null;
                return (ItemStack) builder.getClass().getMethod("build").invoke(builder);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public String keyOf(ItemStack stack) {
            try {
                Class<?> clazz = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
                return (String) clazz.getMethod("getIdByItem", ItemStack.class).invoke(null, stack);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Nexo (successor to Oraxen) — {@code com.nexomc.nexo.api.NexoItems}. */
    private static final class NexoProvider implements Provider {
        @Override public String id() { return "nexo"; }

        @Override public boolean available() {
            return hasClass("Nexo", "com.nexomc.nexo.api.NexoItems");
        }

        @Override public ItemStack build(String key) {
            try {
                Class<?> clazz = Class.forName("com.nexomc.nexo.api.NexoItems");
                Object builder = clazz.getMethod("itemFromId", String.class).invoke(null, key);
                if (builder == null) return null;
                return (ItemStack) builder.getClass().getMethod("build").invoke(builder);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override public String keyOf(ItemStack stack) {
            try {
                Class<?> clazz = Class.forName("com.nexomc.nexo.api.NexoItems");
                return (String) clazz.getMethod("idFromItem", ItemStack.class).invoke(null, stack);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * Vanilla {@code custom-model-data} items.
     *
     * <p>For servers that use a resource pack but don't have a custom item
     * plugin. Key format: {@code MATERIAL:model}.</p>
     */
    private static final class ModelDataProvider implements Provider {
        @Override public String id() { return "cmd"; }

        @Override public boolean available() { return true; }

        @Override public ItemStack build(String key) {
            int colon = key.lastIndexOf(':');
            if (colon <= 0) return null;
            org.bukkit.Material material =
                    org.bukkit.Material.matchMaterial(key.substring(0, colon).toUpperCase(Locale.ROOT));
            if (material == null) return null;
            int model;
            try {
                model = Integer.parseInt(key.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            ItemStack stack = new ItemStack(material);
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return null;
            meta.setCustomModelData(model);
            stack.setItemMeta(meta);
            return stack;
        }

        @Override public String keyOf(ItemStack stack) {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null || !meta.hasCustomModelData()) return null;
            return stack.getType().name() + ":" + meta.getCustomModelData();
        }
    }
}
