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
 * Ozel esya eklentileriyle (ItemsAdder / Oraxen / Nexo) ve vanilya
 * {@code custom-model-data} esyalariyla koprulenme.
 *
 * <p>Hicbir saglayici {@code pom.xml}'e derleme bagimliligi olarak eklenmez;
 * hepsi <b>reflection</b> ile cagrilir. Boylece eklenti bu uc eklentiden hicbiri
 * kurulu olmadan da calisir ve biri kaldirildiginda sunucu hata vermez.</p>
 *
 * <p>Kimlik bicimi: {@code saglayici:anahtar} — ornegin {@code itemsadder:ruby},
 * {@code oraxen:mythic_sword}, {@code cmd:DIAMOND_SWORD:10001}.</p>
 */
public final class CustomItemBridge {

    /** Tek bir ozel esya kaynagi. */
    public interface Provider {
        String id();

        /** Eklenti kurulu ve API'si erisilebilir mi? */
        boolean available();

        /** Anahtardan esya uretir; bulunamazsa null. */
        ItemStack build(String key);

        /** Esya bu saglayiciya aitse anahtarini, degilse null doner. */
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
            plugin.getLogger().info("Ozel esya destegi kapali (custom-items.enabled: false).");
            return;
        }

        register("itemsadder", new ItemsAdderProvider());
        register("oraxen", new OraxenProvider());
        register("nexo", new NexoProvider());
        register("cmd", new ModelDataProvider());

        if (providers.isEmpty()) {
            plugin.getLogger().info("Ozel esya saglayicisi bulunamadi; yalnizca vanilya esyalar.");
        } else {
            plugin.getLogger().info("Ozel esya saglayicilari: " + providers.keySet());
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

    // ------------------------------------------------------------------ erisim

    public boolean enabled() {
        return enabled && !providers.isEmpty();
    }

    public boolean placeOnTop() {
        return "TOP".equals(placement);
    }

    public boolean placeAtBottom() {
        return "BOTTOM".equals(placement);
    }

    /** {@code saglayici:anahtar} -> esya; bulunamazsa null. */
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

    /** Esyanin ozel kimligi; ozel esya degilse null. */
    public String identify(ItemStack stack) {
        if (!enabled() || stack == null) return null;
        for (Provider provider : providers.values()) {
            try {
                String key = provider.keyOf(stack);
                if (key != null && !key.isBlank()) return provider.id() + ":" + key;
            } catch (Exception ignored) {
                // Saglayici API'si degistiyse digerleriyle devam et.
            }
        }
        return null;
    }

    /** Bu kimlige siparis verilebilir mi? */
    public boolean allowed(String fullKey) {
        if (fullKey == null) return true;
        String key = fullKey.toLowerCase(Locale.ROOT);
        if (blacklist.contains(key)) return false;
        return whitelist.isEmpty() || whitelist.contains(key);
    }

    /**
     * Teslim edilen esya siparise uyuyor mu?
     *
     * <p>STRICT modda ad ve model verisi de karsilastirilir; LOOSE modda yalnizca
     * ozel kimlik yeterlidir (yipranmis ya da yeniden adlandirilmis esya kabul
     * edilir).</p>
     */
    public boolean matches(String orderCustomId, ItemStack stack) {
        String stackId = identify(stack);
        if (orderCustomId == null) return stackId == null;
        if (!orderCustomId.equals(stackId)) return false;
        if (!strictMatch) return true;

        ItemStack reference = build(orderCustomId);
        if (reference == null) return true;   // ornek uretilemedi: kimlik yeterli
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

    // ================================================================== saglayicilar

    /** Kurulu bir eklentinin sinifi yuklenebiliyor mu? */
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

    /** Nexo (Oraxen'in devami) — {@code com.nexomc.nexo.api.NexoItems}. */
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
     * Vanilya {@code custom-model-data} esyalari.
     *
     * <p>Kaynak paketi kullanan ama ozel esya eklentisi olmayan sunucular icin.
     * Anahtar bicimi: {@code MATERYAL:model}.</p>
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
